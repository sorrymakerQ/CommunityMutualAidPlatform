package com.linlibang.config;

import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * WebSocket 配置类
 * 使用 STOMP 协议实现实时消息推送。
 *
 * 安全加固：
 *   1. 允许的跨域来源可配置（默认同源，杜绝跨站 WebSocket 劫持 CSWSH）；
 *   2. CONNECT 必须携带有效 satoken，失败直接拒绝连接（不再只告警放行）；
 *   3. SUBSCRIBE 鉴权：/user/** 用户队列仅允许订阅本人，防止越权接收他人私信。
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StringRedisTemplate stringRedisTemplate;

    /** 允许的跨域来源（逗号分隔）。为空时仅同源（默认，安全）。 */
    @Value("${linlibang.websocket.allowed-origins:}")
    private String allowedOrigins;

    public WebSocketConfig(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        StompWebSocketEndpointRegistration endpoint = registry.addEndpoint("/ws");
        if (allowedOrigins != null && !allowedOrigins.trim().isEmpty()) {
            List<String> origins = Arrays.stream(allowedOrigins.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            if (!origins.isEmpty()) {
                endpoint.setAllowedOriginPatterns(origins.toArray(new String[0]));
            }
        }
        // 未配置 allowed-origins 时，Spring 默认仅放行同源请求
        endpoint.withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
    }

    /**
     * 配置 STOMP 通道拦截器：CONNECT 认证 + SUBSCRIBE 授权
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null) {
                    return message;
                }

                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    // 1. 认证：必须携带有效 satoken，否则拒绝连接
                    String token = accessor.getFirstNativeHeader("satoken");
                    if (token == null || token.trim().isEmpty()) {
                        throw new MessagingException("未认证：缺少 satoken");
                    }
                    try {
                        Object loginId = StpUtil.getLoginIdByToken(token.trim());
                        if (loginId == null) {
                            throw new MessagingException("未认证：token 无效");
                        }
                        final String userId = loginId.toString();
                        accessor.setUser(new Principal() {
                            @Override
                            public String getName() { return userId; }
                        });
                    } catch (MessagingException me) {
                        throw me;
                    } catch (Exception e) {
                        log.warn("STOMP CONNECT 鉴权失败: {}", e.getMessage());
                        throw new MessagingException("未认证：token 无效或已过期");
                    }
                } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                    // 2. 授权：/user/** 队列只能订阅本人
                    String dest = accessor.getDestination();
                    if (dest != null && dest.startsWith("/user/")) {
                        Principal principal = accessor.getUser();
                        if (principal == null) {
                            throw new MessagingException("未认证：无法订阅用户队列");
                        }
                        // 仅允许通用前缀 /user/queue|topic/...（服务端按 Principal 解析到本人），
                        // 或显式 /user/{本人id}/...；禁止订阅 /user/{他人id}/... 越权
                        String[] seg = dest.split("/");
                        if (seg.length >= 3) {
                            String third = seg[2];
                            if (!third.equals(principal.getName())
                                    && !"queue".equals(third)
                                    && !"topic".equals(third)) {
                                throw new MessagingException("无权订阅他人消息队列");
                            }
                        }
                    }
                }
                return message;
            }
        });
    }
}
