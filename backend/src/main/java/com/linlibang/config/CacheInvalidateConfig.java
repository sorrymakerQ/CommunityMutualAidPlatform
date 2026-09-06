package com.linlibang.config;

import com.linlibang.cache.MultiLevelCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

/**
 * L1 失效广播订阅配置
 *
 * 订阅 MultiLevelCache 的失效广播频道，收到消息后清掉本实例的 Caffeine L1。
 * 用于多实例部署时，A 实例写/删缓存后，B/C 实例的本地缓存同步失效。
 *
 * 可靠性说明：Redis Pub/Sub 是"即发即弃"，订阅方掉线期间的消息会丢；
 * 丢消息的兜底是 L1 的 30 秒短 TTL（脏数据窗口上限），业务上可接受。
 */
@Configuration
public class CacheInvalidateConfig {

    @Bean
    public RedisMessageListenerContainer cacheInvalidateListenerContainer(
            RedisConnectionFactory connectionFactory, MultiLevelCache multiLevelCache) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            String key = new String(message.getBody(), StandardCharsets.UTF_8);
            multiLevelCache.onInvalidateBroadcast(key);
        }, new ChannelTopic(MultiLevelCache.INVALIDATE_CHANNEL));
        return container;
    }
}
