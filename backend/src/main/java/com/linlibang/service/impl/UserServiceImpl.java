package com.linlibang.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.linlibang.dto.LoginFormDTO;
import com.linlibang.dto.RegisterFormDTO;
import com.linlibang.dto.Result;
import com.linlibang.dto.UserDTO;
import com.linlibang.entity.Role;
import com.linlibang.entity.User;
import com.linlibang.entity.UserCredit;
import com.linlibang.mapper.RoleMapper;
import com.linlibang.mapper.UserCreditMapper;
import com.linlibang.mapper.UserMapper;
import com.linlibang.service.UserService;
import com.linlibang.utils.RedisUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 用户服务实现类
 * 使用 JdbcTemplate DAO 进行数据库操作
 */
@Service
public class UserServiceImpl implements UserService {

    @Resource
    private UserMapper userMapper;

    @Resource
    private UserCreditMapper userCreditMapper;

    @Resource
    private RoleMapper roleMapper;

    @Resource
    private PasswordEncoder passwordEncoder;

    @Resource
    private RedisUtils redisUtils;

    /** Redis缓存前缀 */
    private static final String USER_CACHE_PREFIX = "user:info:";

    /** 用户信息缓存时间（分钟） */
    private static final long USER_CACHE_TTL = 30;

    @Override
    public Result login(LoginFormDTO loginForm) {
        // 1. 校验手机号和密码
        String phone = loginForm.getPhone();
        String password = loginForm.getPassword();

        // 2. 根据手机号查询用户（使用 SQL 语句：SELECT * FROM tb_user WHERE phone = ?）
        User user = userMapper.selectByPhone(phone);

        // 3. 用户不存在
        if (user == null) {
            return Result.fail("手机号未注册，请先注册");
        }

        // 4. 用户被禁用
        if (user.getStatus() == 0) {
            return Result.fail("账号已被禁用，请联系管理员");
        }

        // 5. 密码校验
        if (!passwordEncoder.matches(password, user.getPassword())) {
            return Result.fail("密码错误");
        }

        // 6. Sa-Token 登录（自动生成 Token）
        StpUtil.login(user.getId());

        // 7. 获取 Token 值
        String token = StpUtil.getTokenValue();

        // 8. 缓存用户信息到 Redis（credit 来自 tb_user_credit，一并写入缓存；
        //    信用分变更处负责删缓存，保证展示一致性）
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        fillRoleName(userDTO);
        fillCredit(userDTO);
        redisUtils.set(USER_CACHE_PREFIX + user.getId(),
                JSONUtil.toJsonStr(userDTO),
                USER_CACHE_TTL, TimeUnit.MINUTES);

        // 8. 返回 Token
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("token", token);
        resultMap.put("userInfo", userDTO);

        return Result.ok("登录成功", resultMap);
    }

    @Override
    @Transactional
    public Result register(RegisterFormDTO registerForm) {
        // 1. 校验手机号是否已注册（使用 SQL 语句：SELECT * FROM tb_user WHERE phone = ?）
        String phone = registerForm.getPhone();
        User existUser = userMapper.selectByPhone(phone);
        if (existUser != null) {
            return Result.fail("该手机号已注册");
        }

        // 2. 创建用户对象
        User user = new User();
        user.setPhone(phone);
        user.setPassword(passwordEncoder.encode(registerForm.getPassword()));
        // 设置默认昵称
        user.setNickname(registerForm.getNickname() != null
                ? registerForm.getNickname()
                : "邻居" + phone.substring(7));
        user.setStatus(1);    // 正常状态
        // RBAC：注册默认分配"普通用户"角色，权限由 tb_role_permission 关联自动继承
        Role defaultRole = roleMapper.selectByCode("user");
        user.setRoleId(defaultRole != null ? defaultRole.getId() : 2L);

        // 3. 保存到数据库（使用 SQL 语句：INSERT INTO tb_user (...) VALUES (...)）
        userMapper.insert(user);

        // 4. 同事务初始化信用分（100 分）——用户与信用分双表同生共死，不会出现"有用户无分"
        userCreditMapper.insertDefault(user.getId());

        return Result.ok("注册成功");
    }

    /**
     * 填充角色编码（RBAC：roleId → role.code，供前端权限判断与公开信息展示）
     */
    private void fillRoleName(UserDTO dto) {
        if (dto.getRoleId() != null) {
            Role role = roleMapper.selectById(dto.getRoleId());
            dto.setRoleName(role != null ? role.getCode() : null);
        }
    }

    /**
     * 填充信用分（从 tb_user_credit 实时查，主键单查极廉价）
     */
    private void fillCredit(UserDTO dto) {
        UserCredit uc = userCreditMapper.selectByUserId(dto.getId());
        dto.setCredit(uc != null ? uc.getCredit() : 100);
    }

    @Override
    public Result getCurrentUser() {
        // 1. 从 Sa-Token 获取当前登录用户ID
        long userId = StpUtil.getLoginIdAsLong();

        // 2. 先从 Redis 查缓存
        String cachedJson = redisUtils.get(USER_CACHE_PREFIX + userId);
        if (cachedJson != null) {
            UserDTO userDTO = cn.hutool.json.JSONUtil.toBean(cachedJson, UserDTO.class);
            return Result.ok(userDTO);
        }

        // 3. 缓存未命中，查数据库（使用 SQL 语句：SELECT * FROM tb_user WHERE id = ?）
        User user = userMapper.selectById(userId);
        if (user == null) {
            return Result.fail("用户不存在");
        }

        // 4. 写入缓存
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        fillRoleName(userDTO);
        redisUtils.set(USER_CACHE_PREFIX + userId,
                cn.hutool.json.JSONUtil.toJsonStr(userDTO),
                USER_CACHE_TTL, TimeUnit.MINUTES);

        return Result.ok(userDTO);
    }

    @Override
    public Result getUserById(Long userId) {
        UserDTO userDTO;

        // 1. 先从 Redis 查缓存（缓存完整信息，供 getCurrentUser 自身使用）
        String cachedJson = redisUtils.get(USER_CACHE_PREFIX + userId);
        if (cachedJson != null) {
            userDTO = cn.hutool.json.JSONUtil.toBean(cachedJson, UserDTO.class);
        } else {
            // 2. 查数据库（使用 SQL 语句：SELECT * FROM tb_user WHERE id = ?）
            User user = userMapper.selectById(userId);
            if (user == null) {
                return Result.fail("用户不存在");
            }
            // 3. 写入缓存（修复：getUserById 查询后未缓存，导致每次请求都穿透到 DB）
            userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            fillRoleName(userDTO);
            fillCredit(userDTO);
            redisUtils.set(USER_CACHE_PREFIX + userId,
                    cn.hutool.json.JSONUtil.toJsonStr(userDTO),
                    USER_CACHE_TTL, TimeUnit.MINUTES);
        }

        // 4. 返回公开信息（脱敏：手机号掩码，剥离精确经纬度和角色，避免公开接口泄露隐私/枚举管理员）
        return Result.ok(toPublicDTO(userDTO));
    }

    /**
     * 转为对外公开的 UserDTO：手机号脱敏，剥离精确经纬度、角色与余额
     * （余额属隐私数据，公开接口按 ID 遍历可筛选高余额用户做精准诈骗目标）
     */
    private UserDTO toPublicDTO(UserDTO src) {
        UserDTO pub = BeanUtil.copyProperties(src, UserDTO.class);
        pub.setPhone(maskPhone(src.getPhone()));
        pub.setLng(null);
        pub.setLat(null);
        pub.setRoleName(null);
        pub.setBalance(null);
        return pub;
    }

    /** 手机号脱敏：138****1234 */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    @Override
    @Transactional
    public Result updateUser(UserDTO userDTO) {
        long userId = StpUtil.getLoginIdAsLong();
        User user = userMapper.selectById(userId);
        if (user == null) {
            return Result.fail("用户不存在");
        }

        // 更新允许修改的字段
        if (userDTO.getNickname() != null) {
            user.setNickname(userDTO.getNickname());
        }
        if (userDTO.getAvatar() != null) {
            user.setAvatar(userDTO.getAvatar());
        }
        if (userDTO.getGender() != null) {
            user.setGender(userDTO.getGender());
        }
        if (userDTO.getCommunity() != null) {
            user.setCommunity(userDTO.getCommunity());
        }
        if (userDTO.getIntro() != null) {
            user.setIntro(userDTO.getIntro());
        }

        // 使用 SQL 语句：UPDATE tb_user SET ... WHERE id = ?
        userMapper.updateById(user);

        // 删除缓存
        redisUtils.delete(USER_CACHE_PREFIX + userId);

        return Result.ok("个人信息更新成功");
    }

}
