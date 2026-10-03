package com.linlibang.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置：仅用于分布式锁（接单互斥）。
 *
 * 独立于 Spring Data Redis（Lettuce）——刻意不引入 redisson-spring-boot-starter，
 * 避免其 RedissonConnectionFactory 抢占 StringRedisTemplate，现有 Redis 读写不受影响。
 * RedissonClient 是单独的 Bean，只服务 RLock 分布式锁。
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.redis.host:localhost}")
    private String host;

    @Value("${spring.redis.port:6379}")
    private int port;

    @Value("${spring.redis.password:}")
    private String password;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        SingleServerConfig server = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(0)
                .setConnectTimeout(3000)   // 对应 application.yml 的 connect-timeout
                .setTimeout(3000);          // 对应 application.yml 的 timeout
        if (password != null && !password.isEmpty()) {
            server.setPassword(password);
        }
        return Redisson.create(config);
    }
}
