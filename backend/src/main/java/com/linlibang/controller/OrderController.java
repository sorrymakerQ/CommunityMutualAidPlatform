package com.linlibang.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.linlibang.dto.Result;
import com.linlibang.service.OrderService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Map;

/**
 * 订单控制器
 * 处理接单、取消、完成、评价等请求
 *
 * 当前登录用户由 Service 层用 StpUtil 获取，控制器不再解析 userId 往下传。
 */
@RestController
@RequestMapping("/order")
public class OrderController {

    @Resource
    private OrderService orderService;

    /**
     * 接单（即接即录用）— 校验通过即生成订单并占用名额，无需发布者审批
     *
     * @param helpId 求助ID
     */
    @SaCheckPermission("order:accept")
    @PostMapping("/accept/{helpId}")
    public Result acceptOrder(@PathVariable Long helpId) {
        return orderService.acceptOrder(helpId);
    }

    /**
     * 取消订单 — 需登录
     *
     * @param id   订单ID
     * @param body 包含取消原因
     */
    @SaCheckLogin
    @PutMapping("/{id}/cancel")
    public Result cancelOrder(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String reason = body.getOrDefault("reason", "用户主动取消");
        return orderService.cancelOrder(id, reason);
    }

    /**
     * 确认完成订单 — 需登录
     *
     * @param id 订单ID
     */
    @SaCheckLogin
    @PutMapping("/{id}/finish")
    public Result finishOrder(@PathVariable Long id) {
        return orderService.finishOrder(id);
    }

    /**
     * 评价订单 — 需登录
     *
     * @param id   订单ID
     * @param body 包含评分和评价内容 { score: 5, comment: "很好" }
     */
    @SaCheckLogin
    @PutMapping("/{id}/review")
    public Result reviewOrder(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Object scoreObj = body.get("score");
        if (scoreObj == null) {
            return Result.fail("评分不能为空");
        }
        Integer score = scoreObj instanceof Integer ? (Integer) scoreObj : Integer.valueOf(scoreObj.toString());
        if (score < 1 || score > 5) {
            return Result.fail("评分范围为1-5分");
        }
        String comment = body.get("comment") != null ? body.get("comment").toString() : null;
        return orderService.reviewOrder(id, score, comment);
    }

    /**
     * 查询订单详情 — 需登录
     *
     * @param id 订单ID
     */
    @SaCheckLogin
    @GetMapping("/{id}")
    public Result getOrderById(@PathVariable Long id) {
        return orderService.getOrderById(id);
    }

    /**
     * 查询我的订单 — 需登录
     *
     * @param role 角色：publisher-发布的，helper-接单的，all-全部
     * @param page 页码
     * @param size 每页条数
     */
    @SaCheckLogin
    @GetMapping("/my")
    public Result getMyOrders(
            @RequestParam(required = false, defaultValue = "all") String role,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size) {
        page = Math.max(page, 1);
        size = Math.max(Math.min(size, 50), 1);  // 限制最大每页条数
        return orderService.getMyOrders(role, page, size);
    }
}
