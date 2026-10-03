package com.linlibang.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import com.linlibang.dto.LoginFormDTO;
import com.linlibang.dto.LoginUser;
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
import com.linlibang.utils.AuthUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

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

        // 7. 当前用户信息写入 Sa-Token 会话（框架托管；登出/被踢时自动清除，不再手写 Redis 缓存）
        AuthUtil.setLoginUser(toLoginUser(user));

        // 8. 返回 Token + 用户信息（前端登录后需要 roleName 判断能否进管理后台）
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        fillRoleName(userDTO);
        fillCredit(userDTO);

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("token", StpUtil.getTokenValue());
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
     * 组装会话用的登录用户信息（只含身份字段，不含余额，避免把敏感数据放进会话）
     */
    private LoginUser toLoginUser(User user) {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(user.getId());
        loginUser.setPhone(user.getPhone());
        loginUser.setNickname(user.getNickname());
        loginUser.setAvatar(user.getAvatar());
        loginUser.setGender(user.getGender());
        loginUser.setRoleId(user.getRoleId());
        Role role = user.getRoleId() != null ? roleMapper.selectById(user.getRoleId()) : null;
        loginUser.setRoleCode(role != null ? role.getCode() : "user");
        return loginUser;
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
        // 从 Sa-Token 获取当前登录用户ID，直接按主键查库（已取消手写 Redis 用户缓存）
        long userId = StpUtil.getLoginIdAsLong();

        User user = userMapper.selectById(userId);
        if (user == null) {
            return Result.fail("用户不存在");
        }

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        fillRoleName(userDTO);
        fillCredit(userDTO);
        return Result.ok(userDTO);
    }

    @Override
    public Result getUserById(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return Result.fail("用户不存在");
        }

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        fillRoleName(userDTO);
        fillCredit(userDTO);

        // 返回公开信息（脱敏：手机号掩码，剥离精确经纬度和角色，避免公开接口泄露隐私/枚举管理员）
        return Result.ok(toPublicDTO(userDTO));
    }

    /**
     * 转为对外公开的 UserDTO：手机号脱敏，剥离角色与余额
     * （余额属隐私数据，公开接口按 ID 遍历可筛选高余额用户做精准诈骗目标；
     *   省市区粗粒度 address 保留展示）
     */
    private UserDTO toPublicDTO(UserDTO src) {
        UserDTO pub = BeanUtil.copyProperties(src, UserDTO.class);
        pub.setPhone(maskPhone(src.getPhone()));
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
        if (userDTO.getAddressId() != null) {
            user.setAddressId(userDTO.getAddressId());
        }
        if (userDTO.getIntro() != null) {
            user.setIntro(userDTO.getIntro());
        }

        // 使用 SQL 语句：UPDATE tb_user SET ... WHERE id = ?
        userMapper.updateById(user);

        // 同步 Sa-Token 会话里的用户信息（昵称/头像等改动立即生效，无需等 token 过期）
        AuthUtil.refresh(toLoginUser(user));

        return Result.ok("个人信息更新成功");
    }

}
