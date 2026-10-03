package com.linlibang.utils;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.linlibang.dto.LoginUser;

/**
 * 当前登录用户工具类
 *
 * 用户信息存放在 Sa-Token 的 Account-Session 里（key = satoken:login:session:{loginId}），
 * 由框架负责读写与生命周期：登录时写入、登出/被踢时自动清除、过期随 token 失效。
 * 本项目已不再手写 Redis 缓存 user:info:{id}。
 *
 * 用法：
 *   LoginUser me = AuthUtil.getLoginUser();      // 可能为 null（未登录/老会话）
 *
 * 注意：SaSession 是数据快照，set/delete 之后必须调用 update() 才会写回存储层。
 */
public final class AuthUtil {

    /** 会话中存放当前用户信息的 key */
    public static final String SESSION_KEY = "loginUser";

    private AuthUtil() {
    }

    /** 登录成功后写入当前用户信息（登录流程调用一次） */
    public static void setLoginUser(LoginUser user) {
        SaSession session = StpUtil.getSession();
        session.set(SESSION_KEY, user);
        session.update();
    }

    /**
     * 获取当前登录用户信息
     *
     * @return 未登录或会话中没有该数据时返回 null（调用方可回源数据库）
     */
    public static LoginUser getLoginUser() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        SaSession session = StpUtil.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.get(SESSION_KEY);
        return value instanceof LoginUser ? (LoginUser) value : null;
    }

    /** 更新会话中的用户信息（改昵称/头像/角色后调用，保持会话与库一致） */
    public static void refresh(LoginUser user) {
        setLoginUser(user);
    }
}
