package com.pg.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** 显式开发登录通道；同时满足环境、开关和前缀条件才生成独立模拟身份。 */
@Component
public class MockWxLogin {
    private final Environment environment;
    private final boolean enabled;

    /**
     * 固定开发通道的启用条件。
     * @param environment 当前运行环境
     * @param enabled 环境显式注入的模拟开关，默认关闭
     */
    public MockWxLogin(Environment environment, @Value("${wx.mock-enabled:false}") boolean enabled) {
        this.environment = environment;
        this.enabled = enabled;
    }

    /**
     * 判断本次请求是否可以使用开发通道。
     * @param code 已验证为非空白的临时码
     * @return 仅 dev、显式开关和 mock 前缀同时成立时返回真
     */
    public boolean supports(String code) {
        return enabled && environment.acceptsProfiles(Profiles.of("dev")) && code.startsWith("mock:");
    }

    /**
     * 从开发用例码派生模拟身份，避免直接把客户端输入当作用户编号。
     * @param code 带 mock 前缀的开发用例码
     * @return 带独立命名空间的稳定模拟身份
     */
    public String code2Session(String code) {
        if (!supports(code)) {
            throw new IllegalArgumentException("开发登录未启用");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            // V001 的 openid 上限为 64；无填充 Base64 加命名空间共 48 字符，不能使用 69 位的十六进制形式。
            return "mock_" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少摘要算法");
        }
    }
}
