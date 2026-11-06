package com.pg.service.impl;

import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.domain.User;
import com.pg.mapper.ProfileMapper;
import com.pg.myutils.JwtUtils;
import com.pg.service.ProfileService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 完成微信账号收敛与本人资料投影，写入仅来自服务端默认值或资料白名单。 */
@Service
public class ProfileServiceImpl implements ProfileService {
    private static final int NICKNAME_LIMIT = 50; // 冻结 §1.2：超长昵称使用独立的 422 错误码。
    private static final int PROFILE_LIMIT = 255; // 头像和简介沿用数据库列长度与冻结接口上限。
    private static final String DEFAULT_AVATAR = "/static/images/avatar/admin.jpg";
    private final ProfileMapper profileMapper;
    private final JwtUtils jwtUtils;
    private final HttpServletRequest request;

    /**
     * 固定账号查询、令牌签发和已验证请求身份。
     * @param profileMapper 账号专用数据访问
     * @param jwtUtils 令牌组件
     * @param request 拦截器写入 uid/role 的当前请求代理
     */
    public ProfileServiceImpl(ProfileMapper profileMapper, JwtUtils jwtUtils, HttpServletRequest request) {
        this.profileMapper = profileMapper;
        this.jwtUtils = jwtUtils;
        this.request = request;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> login(String openid) {
        User user = profileMapper.findByOpenid(openid);
        boolean isNew = false;
        if (user == null) {
            User created = new User();
            String sequence = UUID.randomUUID().toString().replace("-", "");
            created.setUid(sequence);
            created.setUname("微信用户" + sequence);
            created.setAvatar(DEFAULT_AVATAR);
            created.setOpenid(openid);
            // 插入独立提交：唯一键的败方等待胜方提交后重读，不能在旧事务快照里循环读不到胜方。
            try {
                profileMapper.insertAccount(created);
                isNew = true;
            } catch (DuplicateKeyException exception) {
                // 只吞掉同一微信身份的竞争；其它唯一键错误仍按服务器故障处理。
                if (profileMapper.findByOpenid(openid) == null) {
                    throw new BizException(ErrorCode.INTERNAL_ERROR, "账号创建失败");
                }
            }
            user = profileMapper.findByOpenid(openid);
        }
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号不可用");
        }
        // 禁用与"账号不存在"必须区分：被禁用户要看到明确的封禁提示，而不是笼统的登录失败。
        if (!"正常".equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED, "账号已被封禁");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", jwtUtils.generateToken(user.getUid(), user.getRole()));
        data.put("uid", user.getUid());
        data.put("isNew", isNew);
        return data;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> info(String uid) {
        User user = requireUser(uid);
        Map<String, Object> data = self(user);
        data.putAll(legacy(user));
        return data;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> publicProfile(String uid) {
        String role = (String) request.getAttribute("role");
        if (!"管理员".equals(role)
                && !("普通用户".equals(role) && uid.equals(request.getAttribute("uid")))) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅可查看本人资料");
        }
        return legacy(requireUser(uid));
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public Map<String, Object> updateProfile(String uid, Map<String, Object> changes) {
        if (changes == null) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "资料必须为对象");
        }
        // 先校验完整补丁，再发出唯一一条更新；任何非法字段都不能导致部分保存。
        Map<String, Object> allowed = new LinkedHashMap<>();
        for (String field : Arrays.asList("nickname", "avatar", "bio")) {
            if (!changes.containsKey(field)) {
                continue;
            }
            Object value = changes.get(field);
            if (!(value instanceof String)) {
                throw new BizException(ErrorCode.INVALID_REQUEST, "资料字段必须为字符串");
            }
            String text = (String) value;
            if (!"bio".equals(field) && text.codePoints().allMatch(Character::isWhitespace)) {
                throw new BizException(ErrorCode.INVALID_REQUEST, "昵称和头像不能为空白");
            }
            int length = text.codePointCount(0, text.length());
            if ("nickname".equals(field) && length > NICKNAME_LIMIT) {
                throw new BizException(ErrorCode.INVALID_PROFILE, "昵称超过长度限制");
            }
            if (!"nickname".equals(field) && length > PROFILE_LIMIT) {
                throw new BizException(ErrorCode.INVALID_REQUEST, "资料超过长度限制");
            }
            allowed.put(field, text);
        }
        requireUser(uid);
        if (!allowed.isEmpty()) {
            profileMapper.updateProfile(uid, allowed);
        }
        return self(requireUser(uid));
    }

    private User requireUser(String uid) {
        User user = profileMapper.findById(uid);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private Map<String, Object> self(User user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("uid", user.getUid());
        data.put("nickname", user.getUname());
        data.put("avatar", user.getAvatar());
        data.put("bio", user.getBio());
        data.put("role", user.getRole());
        return data;
    }

    private Map<String, Object> legacy(User user) {
        user.maskPhoneFor((String) request.getAttribute("role"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("uid", user.getUid());
        data.put("uname", user.getUname());
        data.put("pnum", user.getPnum());
        data.put("role", user.getRole());
        data.put("avatar", user.getAvatar());
        data.put("vote", user.getVote());
        data.put("uct", user.getUct());
        data.put("status", user.getStatus());
        return data;
    }
}
