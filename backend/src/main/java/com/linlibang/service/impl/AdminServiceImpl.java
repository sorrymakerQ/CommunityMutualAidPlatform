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
import com.linlibang.service.HelpRequestService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理端服务（RBAC：只有「管理员 / 普通用户」两种角色）
 *
 * 角色与权限落在数据库里（tb_role / tb_permission / tb_role_permission）：
 *   admin → help:manage（改任意求助状态/下架/删除）、user:kickout（踢人下线）、user:manage（用户管理）
 *   user  → help:publish、order:accept、message:send
 */
@Service
public class AdminServiceImpl implements AdminService {

    /** 角色ID常量：与 tb_role 一致 */
    private static final long ROLE_ADMIN = 1L;
    private static final long ROLE_USER = 2L;

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
    private HelpRequestService helpRequestService;

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
        // 信用分在 tb_user_credit，批量补查后并入返回（前端字段不变）
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
        if (user.getIsBuiltin() != null && user.getIsBuiltin() == 1) {
            return Result.fail("系统内置账号不可禁用");
        }
        if (user.getRoleId() != null && user.getRoleId() == ROLE_ADMIN) {
            return Result.fail("不能禁用管理员账号");
        }
        user.setStatus(status);
        userMapper.updateById(user);
        // 禁用后立即踢下线（会话里的用户信息随会话一起清除）
        if (status != null && status == 0) {
            StpUtil.logout(id);
        }
        return Result.ok(status != null && status == 1 ? "用户已启用" : "用户已禁用");
    }

    /**
     * 修改用户角色（只允许管理员/普通用户两种）
     *
     * 角色变了权限立刻变，但 Sa-Token 会话里缓存着角色信息，
     * 因此改完强制该用户重新登录，保证权限即时生效。
     */
    @Override
    public Result updateUserRole(Long id, Long roleId) {
        if (roleId == null || (roleId != ROLE_ADMIN && roleId != ROLE_USER)) {
            return Result.fail("角色只能是 1(管理员) 或 2(普通用户)");
        }
        User user = userMapper.selectById(id);
        if (user == null) return Result.fail("用户不存在");
        if (user.getIsBuiltin() != null && user.getIsBuiltin() == 1) {
            return Result.fail("系统内置账号的角色不可修改");
        }
        if (id.equals(StpUtil.getLoginIdAsLong())) {
            return Result.fail("不能修改自己的角色");
        }
        if (user.getRoleId() != null && user.getRoleId().equals(roleId)) {
            return Result.ok("角色未变化");
        }

        Role role = roleMapper.selectById(roleId);
        user.setRoleId(roleId);
        userMapper.updateById(user);
        // 强制重新登录：清 token + 清会话，新角色下次登录生效
        StpUtil.logout(id);
        return Result.ok("角色已更新为「" + (role != null ? role.getName() : roleId) + "」");
    }

    /**
     * 踢用户下线：删除该账号全部 token 与会话（多端一起下线）
     */
    @Override
    public Result kickoutUser(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) return Result.fail("用户不存在");
        if (id.equals(StpUtil.getLoginIdAsLong())) {
            return Result.fail("不能踢自己下线");
        }
        StpUtil.logout(id);
        return Result.ok("已将用户「" + user.getNickname() + "」踢下线");
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

    /**
     * 修改任意用户求助的状态（含下架：status=4），需 help:manage 权限
     */
    @Override
    public Result updateHelpStatus(Long id, Integer status) {
        return helpRequestService.adminUpdateStatus(id, status);
    }

    @Override
    public Result deleteHelp(Long id) {
        HelpRequest help = helpRequestMapper.selectById(id);
        if (help == null) return Result.fail("求助不存在");
        help.setIsDeleted(1);
        helpRequestMapper.updateById(help);
        // 失效详情缓存（L1 Caffeine + L2 Redis）与分页缓存
        helpRequestService.evictHelpCache(id);
        return Result.ok("求助已删除");
    }
}
