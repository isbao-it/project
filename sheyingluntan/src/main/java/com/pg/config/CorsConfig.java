package com.pg.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置类
 * 用于配置Spring MVC的跨域资源共享(CORS)策略
 * 通过实现WebMvcConfigurer接口，重写addCorsMappings方法来设置跨域规则
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")                      // 允许所有接口
                .allowedOriginPatterns("*")            // 允许所有来源（开发阶段）
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)                // 允许携带cookie
                .maxAge(3600);
    }
}