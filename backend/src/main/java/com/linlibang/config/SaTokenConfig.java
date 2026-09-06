package com.linlibang.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 注解鉴权拦截器注册（安全关键）
 *
 * 背景：@SaCheckLogin / @SaCheckPermission / @SaCheckRole 注解只有在
 * SaInterceptor 注册进 Spring MVC 后才会在 preHandle 阶段被执行；
 * 未注册时所有注解鉴权形同虚设（接口匿名可达）。
 *
 * 拦截规则：SaInterceptor 无参构造（不传校验函数）= 只做注解校验——
 * 方法上有 @SaCheck* 注解才拦截；无注解的公开接口
 * （/user/login、/user/register、/help/nearby、/help/search、/category/list 等）不受影响。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor()).addPathPatterns("/**");
    }
}
