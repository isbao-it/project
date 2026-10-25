package com.pg.service.impl;

import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.mapper.AdminUserMapper;
import com.pg.service.AdminUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 用户管理服务；使用共同管理员锁防止并发禁用或删除使管理员归零。 */
@Service
public class AdminUserServiceImpl implements AdminUserService {
    private static final int PAGE_SIZE_MAX = 50; // 冻结 §1.1 分页上限。
    private static final int KEYWORD_LIMIT = 50; // 冻结 §1.4 昵称关键词字符数上限。
    private static final List<String> FIELDS = Arrays.asList("uid", "nickname", "avatar", "bio", "role",
            "status", "pnum", "createdAt");
    private final AdminUserMapper adminUserMapper;

    /** @param adminUserMapper 独立用户投影及保护锁访问组件 */
    public AdminUserServiceImpl(AdminUserMapper adminUserMapper) {
        this.adminUserMapper = adminUserMapper;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> list(String keyword, int page, int size) {
        if (page < 1 || size < 1 || size > PAGE_SIZE_MAX
                || (keyword != null && keyword.codePointCount(0, keyword.length()) > KEYWORD_LIMIT)) {
            throw invalid();
        }
        Long total = adminUserMapper.count(keyword);
        List<Map<String, Object>> rows = adminUserMapper.page(keyword, size, ((long) page - 1) * size);
        // 出口保留所有 nullable 键；入口已经要求管理员，因此 pnum 按冻结契约返回完整值。
        rows.replaceAll(row -> {
            Map<String, Object> user = new LinkedHashMap<>();
            for (String field : FIELDS) {
                user.put(field, row.get(field));
            }
            return user;
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        result.put("total", total);
        return result;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public Map<String, Object> changeStatus(String uid, Object status, String adminUid) {
        validateId(uid);
        if (!"正常".equals(status) && !"禁用".equals(status)) {
            throw invalid();
        }
        if (uid.equals(adminUid) && "禁用".equals(status)) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能禁用自己");
        }
        List<String> admins = adminUserMapper.lockNormalAdmins();
        Map<String, Object> target = requireLockedUser(uid);
        if ("禁用".equals(status)) {
            protectLastAdmin(target, admins);
        }
        adminUserMapper.updateStatus(uid, (String) status);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uid", uid);
        result.put("status", status);
        return result;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public void delete(String uid, String adminUid) {
        validateId(uid);
        // 自我操作优先于最后管理员规则，否则本人会错误收到 409 而非 403。
        if (uid.equals(adminUid)) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能删除自己");
        }
        List<String> admins = adminUserMapper.lockNormalAdmins();
        Map<String, Object> target = requireLockedUser(uid);
        protectLastAdmin(target, admins);
        if (adminUserMapper.countWorks(uid) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "用户仍有作品，不能删除");
        }
        adminUserMapper.delete(uid);
    }

    private Map<String, Object> requireLockedUser(String uid) {
        // 所有写操作先按同一顺序锁管理员集合，再锁目标；反过来会在互删时发生锁环。
        Map<String, Object> target = adminUserMapper.findForUpdate(uid);
        if (target == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return target;
    }

    private void protectLastAdmin(Map<String, Object> target, List<String> admins) {
        if ("管理员".equals(target.get("role")) && "正常".equals(target.get("status")) && admins.size() <= 1) {
            throw new BizException(ErrorCode.CONFLICT, "最后一个正常管理员受保护");
        }
    }

    private void validateId(String uid) {
        if (uid == null || !uid.matches("[A-Za-z0-9_-]{1,64}")) {
            throw invalid();
        }
    }

    private BizException invalid() {
        return new BizException(ErrorCode.INVALID_REQUEST, "用户管理参数不合法");
    }
}
