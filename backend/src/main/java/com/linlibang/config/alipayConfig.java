package com.linlibang.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 支付宝配置类
 */
@Data
@Component
@ConfigurationProperties(prefix = "alipay")
public class alipayConfig {

    /** 应用 ID */
    private String appId;

    /** 应用私钥 */
    private String appPrivateKey;

    /** 支付宝公钥 */
    private String alipayPublicKey;

    /** 网关地址（沙箱 openapi.alipaydev.com / 正式 openapi.alipay.com） */
    private String gatewayUrl;

    /** 编码格式 */
    private String charset = "UTF-8";

    /** 请求格式 */
    private String format = "JSON";

    /** 签名方式 */
    private String signType = "RSA2";

    /** 支付宝异步通知地址（ */
    private String notifyUrl;

    /** 支付宝同步跳转地址*/
    private String returnUrl;

    /** 商户号 */
    private String sellerId;


}
