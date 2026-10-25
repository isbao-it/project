package com.pg.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pg.service.WeChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** 微信登录客户端；只提取身份，丢弃会话密钥，外部失败不降级为模拟身份。 */
@Service
public class WeChatClientImpl implements WeChatClient {
    private static final String ENDPOINT = "https://api.weixin.qq.com/sns/jscode2session";
    private static final int TIMEOUT_MILLIS = 5000; // 外部调用最多等待五秒，避免登录请求无限占用线程。
    private static final int INVALID_CODE = 40029;
    private static final int USED_CODE = 40163;
    private final String appid;
    private final String secret;
    private final ObjectMapper objectMapper;

    /**
     * 固定微信凭据与解析器；凭据为空时保留启动能力，在真实登录时返回不可用。
     * @param appid 环境注入的小程序编号
     * @param secret 环境注入的小程序密钥
     * @param objectMapper JSON 解析器
     */
    public WeChatClientImpl(@Value("${wx.appid:}") String appid,
                            @Value("${wx.secret:}") String secret, ObjectMapper objectMapper) {
        this.appid = appid;
        this.secret = secret;
        this.objectMapper = objectMapper;
    }

    /** {@inheritDoc} */
    @Override
    public String code2Session(String code) throws IOException {
        if (appid.trim().isEmpty() || secret.trim().isEmpty()) {
            throw new IOException("微信登录尚未配置");
        }
        // 输入仅作为编码后的查询参数，不记录含凭据的 URL 或微信原始响应。
        String query = "?appid=" + URLEncoder.encode(appid, "UTF-8")
                + "&secret=" + URLEncoder.encode(secret, "UTF-8")
                + "&js_code=" + URLEncoder.encode(code, "UTF-8") + "&grant_type=authorization_code";
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT + query).openConnection();
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("微信登录服务不可用");
            }
            try (InputStream input = connection.getInputStream()) {
                JsonNode response = objectMapper.readTree(input);
                if (response == null || !response.isObject()) {
                    throw new IOException("微信登录响应无效");
                }
                int error = response.path("errcode").asInt(0);
                if (error == INVALID_CODE || error == USED_CODE) {
                    throw new IllegalArgumentException("微信登录码无效或已使用");
                }
                JsonNode identity = response.get("openid");
                if (error != 0 || identity == null || !identity.isTextual()
                        || identity.asText().trim().isEmpty()) {
                    throw new IOException("微信登录服务不可用");
                }
                // 出口仅返回身份；session_key 不保留、不记录、不交给控制器。
                return identity.asText();
            }
        } finally {
            connection.disconnect();
        }
    }
}
