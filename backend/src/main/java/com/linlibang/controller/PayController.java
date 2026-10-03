package com.linlibang.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.alipay.api.AlipayApiException;
import com.linlibang.dto.Result;
import com.linlibang.service.PayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

/**
 * 支付控制器（余额支付 + 支付宝）
 *
 * 当前登录用户由 Service 层用 StpUtil 获取，控制器不再解析 userId 往下传。
 */
@Slf4j
@RestController
@RequestMapping("/pay")
public class PayController {

    @Resource
    private PayService payService;

    /** 支付求助：余额扣减 */
    @SaCheckPermission("help:publish")
    @PostMapping("/{helpId}")
    public Result pay(@PathVariable Long helpId) {
        return payService.balancepay(helpId);
    }

    /** 支付宝支付 */
    @SaCheckLogin
    @PostMapping("/alipay/{helpId}")
    public Result alipay(@PathVariable Long helpId) {
        return payService.alipay(helpId);
    }

    /**
     * 支付宝异步通知
     *
     * 注意两点：
     *   1. HttpServletRequest 是 Servlet 容器注入的，**不能加 @RequestBody**
     *      （加了会被当成"用转换器把报文转成 HttpServletRequest"，直接 415）；
     *   2. 返回体必须是裸字符串 "success"，不能用 Result 包成 JSON。
     */
    @PostMapping(value = "/notify", produces = "text/plain;charset=UTF-8")
    public String notify(HttpServletRequest request) throws AlipayApiException {
        Boolean ifsuccess= payService.notify(request);
        if(ifsuccess){
            return "success";
        }else{
            return "fail";
        }
}
}
