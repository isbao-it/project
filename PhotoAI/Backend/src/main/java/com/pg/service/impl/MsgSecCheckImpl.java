package com.pg.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.mapper.WorkQueryMapper;
import com.pg.service.MsgSecCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 微信文本安全客户端；仅明确通过才放行，拒绝与外部故障分别返回 422/503。 */
@Service
public class MsgSecCheckImpl implements MsgSecCheck {
    private static final String TOKEN_ENDPOINT = "https://api.weixin.qq.com/cgi-bin/token";
    private static final String CHECK_ENDPOINT = "https://api.weixin.qq.com/wxa/msg_sec_check";
    private static final int TIMEOUT_MILLIS = 5000; // 与现有微信客户端一致，单次网络等待最多五秒。
    private static final int REJECTED_CODE = 87014; // 微信内容不通过错误码。
    private static final int API_VERSION = 2; // 微信 msg_sec_check 2.0。
    private static final int CONTENT_SCENE = 2; // 评论/用户发布文本检查，供卡05复用。
    private static final long TOKEN_MARGIN_SECONDS = 60; // 提前失效，避免检查途中凭据过期。
    private final WorkQueryMapper workQueryMapper;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final String appid;
    private final String secret;
    private final boolean enabled;
    private String accessToken;
    private long tokenExpiresAt;

    /**
     * 注入真实客户端依赖及显式开关；关闭开关仅在 dev 生效。
     * @param workQueryMapper 内部微信身份查询
     * @param objectMapper JSON 编解码
     * @param environment 当前 profile
     * @param appid 微信应用号
     * @param secret 微信密钥
     * @param enabled 检查开关，默认开启
     */
    public MsgSecCheckImpl(WorkQueryMapper workQueryMapper, ObjectMapper objectMapper, Environment environment,
                           @Value("${wx.appid:}") String appid, @Value("${wx.secret:}") String secret,
                           @Value("${wx.msg-sec-check-enabled:${WX_MSG_SEC_CHECK_ENABLED:true}}") boolean enabled) {
        this.workQueryMapper = workQueryMapper;
        this.objectMapper = objectMapper;
        this.environment = environment;
        this.appid = appid;
        this.secret = secret;
        this.enabled = enabled;
    }

    /** {@inheritDoc} */
    @Override
    public void check(String uid, String content) {
        if (!enabled && environment.acceptsProfiles(Profiles.of("dev"))) {
            return;
        }
        if (content == null) {
            throw unavailable();
        }
        // 空描述没有需要送检的字符；发布标题仍必须经过真实检查。
        if (content.isEmpty()) {
            return;
        }
        if (appid.trim().isEmpty() || secret.trim().isEmpty()) {
            throw unavailable();
        }
        String identity = workQueryMapper.securityIdentity(uid);
        if (identity == null || identity.trim().isEmpty()) {
            throw unavailable();
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("openid", identity);
            body.put("content", content);
            body.put("version", API_VERSION);
            body.put("scene", CONTENT_SCENE);
            JsonNode response = exchange(CHECK_ENDPOINT + "?access_token=" + encode(token()), body);
            JsonNode error = response.get("errcode");
            if (error == null || !error.isIntegralNumber()) {
                throw unavailable();
            }
            if (error.asInt() == REJECTED_CODE) {
                throw rejected();
            }
            if (error.asInt() != 0) {
                throw unavailable();
            }
            String suggestion = response.path("result").path("suggest").asText();
            if ("risky".equals(suggestion) || "review".equals(suggestion)) {
                throw rejected();
            }
            if (!"pass".equals(suggestion)) {
                throw unavailable();
            }
        } catch (IOException exception) {
            // 不记录异常消息、请求 URL、微信原文；其中可能含凭据或用户内容。
            throw unavailable();
        }
    }

    private synchronized String token() throws IOException {
        long now = System.currentTimeMillis();
        if (accessToken != null && now < tokenExpiresAt) {
            return accessToken;
        }
        JsonNode response = exchange(TOKEN_ENDPOINT + "?grant_type=client_credential&appid="
                + encode(appid) + "&secret=" + encode(secret), null);
        JsonNode token = response.get("access_token");
        JsonNode expires = response.get("expires_in");
        if (token == null || !token.isTextual() || token.asText().trim().isEmpty()
                || expires == null || !expires.isIntegralNumber() || expires.asLong() <= TOKEN_MARGIN_SECONDS) {
            throw unavailable();
        }
        accessToken = token.asText();
        tokenExpiresAt = now + (expires.asLong() - TOKEN_MARGIN_SECONDS) * 1000;
        return accessToken;
    }

    private JsonNode exchange(String endpoint, Map<String, Object> body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setInstanceFollowRedirects(false);
        try {
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] payload = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload);
                }
            }
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw unavailable();
            }
            try (InputStream input = connection.getInputStream()) {
                JsonNode response = objectMapper.readTree(input);
                if (response == null || !response.isObject()) {
                    throw unavailable();
                }
                return response;
            }
        } finally {
            connection.disconnect();
        }
    }

    private String encode(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }

    private BizException rejected() {
        return new BizException(ErrorCode.CONTENT_REJECTED, "内容未通过安全检查");
    }

    private BizException unavailable() {
        return new BizException(ErrorCode.SERVICE_UNAVAILABLE, "文本安全服务暂不可用");
    }
}
