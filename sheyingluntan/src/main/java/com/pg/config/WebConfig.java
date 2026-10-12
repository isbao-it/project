package com.pg.config;

import com.pg.interceptor.AuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
//为了让浏览器能通过 /static/images/avatar/xxx.jpg 访问到 uploads/avatar/ 目录下的图片
public class WebConfig implements WebMvcConfigurer {

    @Value("${file.upload.avatar}")
    private String avatarUploadPath;
    @Autowired
    private AuthInterceptor authInterceptor;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 获取项目根目录绝对路径
        String projectRoot = System.getProperty("user.dir");
        // 将 /static/images/avatar/** 映射到外部上传目录
        registry.addResourceHandler("/static/images/avatar/**")
                .addResourceLocations("file:" + projectRoot + "/" + avatarUploadPath)
                .setCachePeriod(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 拦截所有请求，具体的放行规则在拦截器内部定义
        registry.addInterceptor(authInterceptor).addPathPatterns("/**");
    }


}