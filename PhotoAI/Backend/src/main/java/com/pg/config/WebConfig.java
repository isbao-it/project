package com.pg.config;

import com.pg.interceptor.AuthInterceptor;
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
    private final AuthInterceptor authInterceptor;

    /**
     * 创建组件并固定依赖，由 Spring 完成构造器注入。
     * @param authInterceptor 请求鉴权拦截器
     */
    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 获取项目根目录绝对路径
        String projectRoot = System.getProperty("user.dir");
        // 将 /static/images/avatar/** 映射到外部上传目录
        registry.addResourceHandler("/static/images/avatar/**")
                .addResourceLocations("file:" + projectRoot + "/" + avatarUploadPath)
                .setCachePeriod(3600);
        // 只开放重编码后的作品目录，不映射原始上传或项目根目录。
        registry.addResourceHandler("/uploads/work/**")
                .addResourceLocations(new java.io.File(projectRoot, "uploads/work/").toURI().toString())
                .setCachePeriod(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 拦截所有请求，具体的放行规则在拦截器内部定义
        registry.addInterceptor(authInterceptor).addPathPatterns("/**");
    }


}
