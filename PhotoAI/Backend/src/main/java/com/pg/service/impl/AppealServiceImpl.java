package com.pg.service.impl;

import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.mapper.AppealMapper;
import com.pg.service.AppealService;
import com.pg.service.WeChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 用微信身份自证的申诉服务，接受申诉只恢复账号、不改作品可见性。 */
@Service
public class AppealServiceImpl implements AppealService {
    private static final int TEXT_LIMIT = 200; // ADMIN §7 申诉原文和驳回理由上限，按 Unicode 字符计。
    private static final List<String> STATUSES = Arrays.asList("待处理", "已受理", "已驳回");
    private static final List<String> FIELDS = Arrays.asList("aid", "uid", "nickname", "avatar", "content",
            "status", "ct", "handler", "hct", "reason");
    private final AppealMapper appealMapper;
    private final WeChatClient weChatClient;
    private final MockWxLogin mockWxLogin;

    /** @param appealMapper 申诉与账号 SQL @param weChatClient 真实微信交换客户端
     * @param mockWxLogin 登录入口共用的开发环境身份规则 */
    public AppealServiceImpl(AppealMapper appealMapper, WeChatClient weChatClient, MockWxLogin mockWxLogin) {
        this.appealMapper = appealMapper;
        this.weChatClient = weChatClient;
        this.mockWxLogin = mockWxLogin;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public Map<String, Object> submit(Map<String, Object> body) {
        Object content = body == null ? null : body.get("content");
        if (!validText(content, TEXT_LIMIT)) {
            throw invalid();
        }
        // 入口只接受 code 换来的 openid，客户端传 uid 或 openid 都不能替换本人身份。
        String openid = exchange(body.get("code"));
        Map<String, Object> user = appealMapper.lockUser(openid);
        if (user == null || "正常".equals(user.get("status"))) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "账号不存在或无需申诉");
        }
        String uid = (String) user.get("uid");
        // 锁住本人账号后再计数，防止两个并发提交都看见零条；待处理冲突优先于分钟限流。
        if (appealMapper.pendingCount(uid) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "已有一条待处理申诉");
        }
        if (appealMapper.minuteCount(uid) > 0) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "请求过于频繁");
        }
        String aid = UUID.randomUUID().toString();
        Map<String, Object> appeal = new LinkedHashMap<>();
        appeal.put("aid", aid);
        appeal.put("uid", uid);
        appeal.put("content", content);
        if (appealMapper.insert(appeal) != 1) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "申诉写入失败");
        }
        return state(aid, "待处理");
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(Map<String, String> parameters) {
        String status = parameters.get("status");
        int page = number(parameters.get("page"), 1);
        int size = number(parameters.get("size"), 10);
        if ((status != null && !STATUSES.contains(status)) || page < 1 || size < 1 || size > 50) {
            throw invalid();
        }
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("status", status);
        query.put("size", size);
        query.put("offset", ((long) page - 1) * size);
        Long total = appealMapper.count(status);
        List<Map<String, Object>> rows = appealMapper.page(query);
        // 出口补齐 LEFT JOIN 或 MyBatis 省略的 null，避免已删除用户和未处理申诉少键。
        rows.replaceAll(row -> {
            Map<String, Object> projected = new LinkedHashMap<>();
            for (String field : FIELDS) {
                projected.put(field, row.get(field));
            }
            return projected;
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        result.put("total", total);
        return result;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public Map<String, Object> handle(String aid, Map<String, Object> body, String handler) {
        Object result = body == null ? null : body.get("result");
        Object reason = body == null ? null : body.get("reason");
        if ((!"接受".equals(result) && !"驳回".equals(result))
                || ("驳回".equals(result) && !validText(reason, TEXT_LIMIT))) {
            throw invalid();
        }
        Map<String, Object> appeal = appealMapper.lockAppeal(aid);
        if (appeal == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "申诉不存在");
        }
        if (!"待处理".equals(appeal.get("status"))) {
            throw new BizException(ErrorCode.CONFLICT, "申诉已处理");
        }
        String status = "接受".equals(result) ? "已受理" : "已驳回";
        appeal.put("status", status);
        appeal.put("handler", handler);
        appeal.put("reason", "驳回".equals(result) ? reason : null);
        if (appealMapper.handle(appeal) != 1) {
            throw new BizException(ErrorCode.CONFLICT, "申诉已处理");
        }
        // 两项更新必须共用事务；用户已删除时只记录受理，不创建账号或改动作品。
        if ("接受".equals(result)) {
            appealMapper.enableUser((String) appeal.get("uid"));
        }
        return state(aid, status);
    }

    private String exchange(Object value) {
        if (!(value instanceof String) || !validText(value, Integer.MAX_VALUE)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "微信登录码无效或已使用");
        }
        String code = (String) value;
        // 与 AuthController 使用同一个 Bean 判定环境、显式开关和前缀，生产不能降级为模拟身份。
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
            throw new BizException(ErrorCode.UNAUTHORIZED, "微信身份验证失败");
        }
        if (openid == null || openid.trim().isEmpty()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "微信登录码无效或已使用");
        }
        return openid;
    }

    private boolean validText(Object value, int limit) {
        if (!(value instanceof String)) {
            return false;
        }
        String text = (String) value;
        return text.codePointCount(0, text.length()) <= limit
                && text.codePoints().anyMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c));
    }

    private int number(String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        if (!text.matches("[0-9]+")) {
            throw invalid();
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private Map<String, Object> state(String aid, String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("aid", aid);
        result.put("status", status);
        return result;
    }

    private BizException invalid() {
        return new BizException(ErrorCode.INVALID_REQUEST, "申诉参数不合法");
    }
}
