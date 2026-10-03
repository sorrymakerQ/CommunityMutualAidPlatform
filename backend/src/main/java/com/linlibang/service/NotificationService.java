package com.linlibang.service;

import com.linlibang.dto.Result;

/**
 * 通知服务
 *
 * 涉及"当前登录用户"的方法不再接收 userId 参数，
 * 由实现类内部用 StpUtil.getLoginIdAsLong() 获取。
 */
public interface NotificationService {
    Result getNotifications(Integer page, Integer size);
    Result readNotification(Long id);
    Result getUnreadCount();
    Result readAll();
}
