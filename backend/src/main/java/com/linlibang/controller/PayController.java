package com.linlibang.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.linlibang.dto.Result;
import com.linlibang.service.PayService;
import com.linlibang.utils.RedisUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 支付控制器（简单余额支付）
 */
@RestController
@RequestMapping("/pay")
public class PayController {

    @Resource
    private PayService payService;

    @Resource
    private RedisUtils redisUtils;

    /** 支付求助：余额扣减 → 求助上首页（发布者操作；幂等键防重复支付） */
    @SaCheckPermission("help:publish")
    @PostMapping("/{helpId}")
    public Result pay(@PathVariable Long helpId,
                      @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        long userId = StpUtil.getLoginIdAsLong();
        // 幂等键（Redis SETNX；即使键失效，支付状态机 CAS 仍幂等兜底）
        if (requestId != null && !requestId.trim().isEmpty()) {
            if (!redisUtils.setIfAbsent("idem:pay:" + userId + ":" + requestId.trim(),
                    "1", 30, TimeUnit.MINUTES)) {
                return Result.fail("请勿重复提交");
            }
        }
        return payService.pay(helpId, userId);
    }
}
