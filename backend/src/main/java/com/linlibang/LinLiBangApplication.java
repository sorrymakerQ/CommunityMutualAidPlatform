package com.linlibang;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Slf4j
@EnableAsync
@EnableScheduling
@SpringBootApplication
@MapperScan("com.linlibang.mapper")
public class LinLiBangApplication {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
    public static void main(String[] args) {
        SpringApplication.run(LinLiBangApplication.class, args);
        log.info("========================================");
        log.info("  社区互助平台 启动成功！");
        log.info("  邻里互助，温暖社区");
        log.info("========================================");
    }
}
