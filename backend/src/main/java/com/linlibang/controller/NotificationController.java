package com.linlibang.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.linlibang.service.NotificationService;
import com.linlibang.dto.Result;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    @Resource
    private NotificationService notificationService;

    /** 通知列表 — 需登录 */
    @SaCheckLogin
    @GetMapping
    public Result getNotifications(
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size) {
        page = Math.max(page, 1);
        size = Math.max(Math.min(size, 50), 1);
        return notificationService.getNotifications(StpUtil.getLoginIdAsLong(), page, size);
    }

    /** 标记已读 — 需登录 */
    @SaCheckLogin
    @PutMapping("/{id}/read")
    public Result readNotification(@PathVariable Long id) {
        return notificationService.readNotification(id, StpUtil.getLoginIdAsLong());
    }

    /** 未读数量 — 需登录 */
    @SaCheckLogin
    @GetMapping("/unread-count")
    public Result getUnreadCount() {
        return notificationService.getUnreadCount(StpUtil.getLoginIdAsLong());
    }

    /** 全部已读 — 需登录 */
    @SaCheckLogin
    @PutMapping("/read-all")
    public Result readAllNotifications() {
        return notificationService.readAll(StpUtil.getLoginIdAsLong());
    }
}
