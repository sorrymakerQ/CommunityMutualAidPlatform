package com.linlibang.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.linlibang.dto.Result;
import com.linlibang.entity.Notification;
import com.linlibang.mapper.NotificationMapper;
import com.linlibang.service.NotificationService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class NotificationServiceImpl implements NotificationService {

    @Resource
    private NotificationMapper notificationMapper;

    @Override
    public Result getNotifications(Integer page, Integer size) {
        Long userId = StpUtil.getLoginIdAsLong();
        int pageNum = page != null ? page : 1;
        int pageSize = size != null ? size : 10;
        int offset = (pageNum - 1) * pageSize;
        List<Notification> list = notificationMapper.selectByUserIdPaged(userId, offset, pageSize);
        Long total = notificationMapper.selectCountByUserId(userId);
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("list", list);
        resultMap.put("total", total);
        return Result.ok(resultMap);
    }

    @Override
    public Result readNotification(Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        Notification notification = notificationMapper.selectById(id);
        if (notification == null) return Result.fail("通知不存在");
        if (!notification.getUserId().equals(userId)) return Result.fail("无权操作此通知");
        notificationMapper.updateIsRead(id, 1);
        return Result.ok("已标记为已读");
    }

    @Override
    public Result getUnreadCount() {
        Long userId = StpUtil.getLoginIdAsLong();
        Long count = notificationMapper.selectUnreadCount(userId);
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("count", count);
        return Result.ok(resultMap);
    }

    @Override
    public Result readAll() {
        Long userId = StpUtil.getLoginIdAsLong();
        List<Notification> unreadList = notificationMapper.selectUnreadByUserId(userId);
        for (Notification notification : unreadList) {
            notificationMapper.updateIsRead(notification.getId(), 1);
        }
        return Result.ok("全部已标记为已读");
    }
}
