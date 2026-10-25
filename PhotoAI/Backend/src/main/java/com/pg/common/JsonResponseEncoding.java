package com.pg.common;

import com.pg.entity.AjaxResult;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.nio.charset.StandardCharsets;

/** 给统一 JSON 信封声明实际使用的 UTF-8，避免客户端按默认字符集读取中文。 */
@RestControllerAdvice
public class JsonResponseEncoding implements ResponseBodyAdvice<AjaxResult> {
    /**
     * 只处理统一信封，不改变页面或其它响应格式。
     * @param returnType 控制器返回类型
     * @param converterType MVC 选择的转换器
     * @return 返回类型为信封时启用
     */
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return AjaxResult.class.isAssignableFrom(returnType.getParameterType())
                || ResolvableType.forMethodParameter(returnType).resolveGeneric(0) == AjaxResult.class;
    }

    /**
     * 在写出 JSON 前声明字符集，不修改信封、状态、错误映射或 Jackson 配置。
     * @param body 原始信封
     * @param returnType 返回类型
     * @param selectedContentType 协商后的内容类型
     * @param selectedConverterType 已选转换器
     * @param request 当前请求
     * @param response 将写出的响应
     * @return 原始信封
     */
    @Override
    public AjaxResult beforeBodyWrite(AjaxResult body, MethodParameter returnType, MediaType selectedContentType,
                                      Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                      ServerHttpRequest request, ServerHttpResponse response) {
        response.getHeaders().setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        return body;
    }
}
