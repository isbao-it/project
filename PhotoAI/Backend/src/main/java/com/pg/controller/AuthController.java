package com.pg.controller;

import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.entity.AjaxResult;
import com.pg.service.ProfileService;
import com.pg.service.WeChatClient;
import com.pg.service.impl.MockWxLogin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/** 微信登录入口；临时码只用于交换身份，不信任客户端传入的账号或角色。 */
@RestController
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private final WeChatClient weChatClient;
    private final MockWxLogin mockWxLogin;
    private final ProfileService profileService;

    /**
     * 固定登录依赖。
     * @param weChatClient 真实微信身份交换
     * @param mockWxLogin 显式开发通道
     * @param profileService 账号创建和令牌签发
     */
    public AuthController(WeChatClient weChatClient, MockWxLogin mockWxLogin, ProfileService profileService) {
        this.weChatClient = weChatClient;
        this.mockWxLogin = mockWxLogin;
        this.profileService = profileService;
    }

    /**
     * 验证临时码并登录，首次身份将创建账号。
     * @param body JSON 对象，code 必须为非空白字符串
     * @return 仅含 token、uid、isNew 的成功信封
     */
    @PostMapping("/auth/wxlogin")
    public AjaxResult wxlogin(@RequestBody Map<String, Object> body) {
        Object value = body == null ? null : body.get("code");
        if (!(value instanceof String) || ((String) value).codePoints().allMatch(Character::isWhitespace)) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "微信登录码不能为空");
        }
        String code = (String) value;
        boolean mock = mockWxLogin.supports(code);
        if (code.startsWith("mock:") && !mock) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "开发登录未启用");
        }
        String openid;
        try {
            openid = mock ? mockWxLogin.code2Session(code) : weChatClient.code2Session(code);
        } catch (IllegalArgumentException exception) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "微信登录码无效或已使用");
        } catch (IOException exception) {
            log.warn("微信登录服务暂不可用");
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "微信登录服务暂不可用");
        }
        if (openid == null || openid.trim().isEmpty()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "微信登录码无效或已使用");
        }
        return AjaxResult.success(profileService.login(openid));
    }
}
