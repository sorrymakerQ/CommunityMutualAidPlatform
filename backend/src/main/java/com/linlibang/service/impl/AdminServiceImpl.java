package com.linlibang.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import com.linlibang.dto.Result;
import com.linlibang.entity.HelpRequest;
import com.linlibang.entity.Role;
import com.linlibang.entity.User;
import com.linlibang.entity.UserCredit;
import com.linlibang.mapper.HelpRequestMapper;
import com.linlibang.mapper.OrderMapper;
import com.linlibang.mapper.RoleMapper;
import com.linlibang.mapper.UserCreditMapper;
import com.linlibang.mapper.UserMapper;
import com.linlibang.service.AdminService;
import com.linlibang.utils.RedisUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AdminServiceImpl implements AdminService {

    @Resource
    private UserMapper userMapper;

    @Resource
    private UserCreditMapper userCreditMapper;

    @Resource
    private RoleMapper roleMapper;

    @Resource
    private HelpRequestMapper helpRequestMapper;

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private RedisUtils redisUtils;

    @Override
    public Result getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("userCount", userMapper.selectCount());
        stats.put("helpCount", helpRequestMapper.selectCount(null));
        stats.put("pendingHelpCount", helpRequestMapper.selectCountByStatus(1));
        stats.put("finishedOrderCount", orderMapper.selectCountByStatus(3));
        return Result.ok(stats);
    }

    @Override
    public Result getUserList(Integer page, Integer size) {
        page = Math.max(page, 1);
        size = Math.max(Math.min(size, 50), 1);
        int offset = (page - 1) * size;
        List<User> list = userMapper.selectPage(offset, size);
        // 安全：管理员列表不返回密码哈希（避免通过 Network 面板泄露 BCrypt 值）
        list.forEach(u -> u.setPassword(null));
        Long total = userMapper.selectCount();
        // 信用分已拆分到 tb_user_credit，批量补查后并入返回（前端字段不变）
        List<Long> userIds = list.stream().map(User::getId).collect(Collectors.toList());
        Map<Long, Integer> creditMap = userIds.isEmpty() ? new HashMap<>()
                : userCreditMapper.selectByUserIds(userIds).stream()
                        .collect(Collectors.toMap(UserCredit::getUserId, UserCredit::getCredit, (a, b) -> a));
        List<Map<String, Object>> rows = list.stream().map(u -> {
            Map<String, Object> row = BeanUtil.beanToMap(u);
            row.put("credit", creditMap.getOrDefault(u.getId(), 100));
            return row;
        }).collect(Collectors.toList());
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("list", rows);
        resultMap.put("total", total);
        return Result.ok(resultMap);
    }

    @Override
    public Result updateUserStatus(Long id, Integer status) {
        User user = userMapper.selectById(id);
        if (user == null) return Result.fail("用户不存在");
        // 系统内置账号（超级管理员等）不可禁用
        if (user.getIsBuiltin() != null && user.getIsBuiltin() == 1) {
            return Result.fail("系统内置账号不可禁用");
        }
        if (user.getRoleId() != null && user.getRoleId() == 1L) {
            return Result.fail("不能禁用管理员账号");
        }
        user.setStatus(status);
        userMapper.updateById(user);
        if (status == 0) StpUtil.logout(id);
        return Result.ok(status == 1 ? "用户已启用" : "用户已禁用");
    }

    @Override
    public Result updateUserRole(Long id, Long roleId) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) return Result.fail("角色不存在");
        User user = userMapper.selectById(id);
        if (user == null) return Result.fail("用户不存在");

        // 当前操作者（管理接口由 @SaCheckRole 保证是 admin 或 super_admin）
        User operator = userMapper.selectById(StpUtil.getLoginIdAsLong());
        boolean isSuper = operator != null && operator.getRoleId() != null && operator.getRoleId() == 4L;

        // 系统内置账号（含超级管理员本人）的角色不可修改
        if (user.getIsBuiltin() != null && user.getIsBuiltin() == 1) {
            return Result.fail("系统内置账号的角色不可修改");
        }
        // 超级管理员角色为系统内置，不可通过接口分配给他人
        if (roleId == 4L) {
            return Result.fail("超级管理员角色为系统内置，不可分配");
        }
        // 涉及管理员角色（设为管理员 或 被操作者当前是管理员）：仅超级管理员可管理
        if (roleId == 1L || (user.getRoleId() != null && user.getRoleId() == 1L)) {
            if (!isSuper) {
                return Result.fail("仅超级管理员可以管理管理员账号");
            }
        }

        user.setRoleId(roleId);
        userMapper.updateById(user);
        // 清除 Redis 缓存 + 强制重新登录，使新角色立即生效
        redisUtils.delete("user:info:" + id);
        StpUtil.logout(id);
        return Result.ok("角色已更新为「" + role.getName() + "」");
    }

    @Override
    public Result getRoleList() {
        return Result.ok(roleMapper.selectAll());
    }

    @Override
    public Result getHelpList(Integer page, Integer size, Integer status) {
        page = Math.max(page, 1);
        size = Math.max(Math.min(size, 50), 1);
        int offset = (page - 1) * size;
        List<HelpRequest> list = helpRequestMapper.selectPageAll(offset, size, status);
        Long total = helpRequestMapper.selectCountAll(status);
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("list", list);
        resultMap.put("total", total);
        return Result.ok(resultMap);
    }

    @Override
    public Result deleteHelp(Long id) {
        HelpRequest help = helpRequestMapper.selectById(id);
        if (help == null) return Result.fail("求助不存在");
        help.setIsDeleted(1);
        helpRequestMapper.updateById(help);

        // 清理 Redis 缓存和 GEO 位置（修复：之前只更新 DB，缓存中仍存在已删除数据）
        redisUtils.delete("help:item:" + id);
        redisUtils.geoRemove("help:location", id.toString());
        // 清除分页缓存
        java.util.Set<String> pageKeys = redisUtils.scanKeys("help:page:*");
        if (pageKeys != null) {
            for (String key : pageKeys) {
                redisUtils.delete(key);
            }
        }

        return Result.ok("求助已删除");
    }
}
