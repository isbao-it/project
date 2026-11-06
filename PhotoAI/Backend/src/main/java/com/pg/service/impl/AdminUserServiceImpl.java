package com.pg.service.impl;

import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.mapper.AdminUserMapper;
import com.pg.service.AdminUserService;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 用户管理服务；使用共同管理员锁防止并发禁用或删除使管理员归零。 */
@Service
public class AdminUserServiceImpl implements AdminUserService {
    private static final int PAGE_SIZE_MAX = 50; // 冻结 §1.1 分页上限。
    private static final int KEYWORD_LIMIT = 50; // 冻结 §1.4 昵称关键词字符数上限。
    private static final List<String> FIELDS = Arrays.asList("uid", "nickname", "avatar", "bio", "role",
            "status", "pnum", "createdAt");
    private final AdminUserMapper adminUserMapper;
    private final SqlSessionTemplate sqlSessionTemplate;

    /** @param adminUserMapper 独立用户投影及保护锁访问组件 @param sqlSessionTemplate 参与同一事务的作品联动访问组件 */
    public AdminUserServiceImpl(AdminUserMapper adminUserMapper, SqlSessionTemplate sqlSessionTemplate) {
        this.adminUserMapper = adminUserMapper;
        this.sqlSessionTemplate = sqlSessionTemplate;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> guards(String uids, String adminUid) {
        if (uids == null) {
            throw invalid();
        }
        // split 保留尾部空项，避免 "uid," 被悄悄当成合法单用户请求。
        String[] ids = uids.split(",", -1);
        if (ids.length > PAGE_SIZE_MAX) {
            throw invalid();
        }
        Set<String> seen = new HashSet<>();
        for (String uid : ids) {
            validateId(uid);
            if (!seen.add(uid)) {
                throw invalid();
            }
        }
        Long normalAdmins = adminUserMapper.countNormalAdmins();
        List<Map<String, Object>> rows = adminUserMapper.guardTargets(Arrays.asList(ids));
        Map<String, Map<String, Object>> targets = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            targets.put((String) row.get("uid"), row);
        }
        List<Map<String, Object>> guards = new ArrayList<>();
        for (String uid : ids) {
            Map<String, Object> target = targets.get(uid);
            if (target == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
            }
            // 本人保护必须优先；只读提示和写接口采用同一优先级，不能把自己误标为最后管理员。
            String reason = null;
            if (uid.equals(adminUid)) {
                reason = "self";
            } else if ("管理员".equals(target.get("role")) && "正常".equals(target.get("status"))
                    && normalAdmins == 1L) {
                reason = "last_admin";
            }
            long workCount = ((Number) target.get("workCount")).longValue();
            String deleteReason = reason;
            if (deleteReason == null && workCount > 0L) {
                deleteReason = "has_works";
            }
            // 出口六键复用批量查询的快照计数；用户列表仍由独立的八键投影约束。
            Map<String, Object> guard = new LinkedHashMap<>();
            guard.put("uid", uid);
            guard.put("canDisable", reason == null);
            guard.put("canDelete", deleteReason == null);
            guard.put("disableReason", reason);
            guard.put("deleteReason", deleteReason);
            guard.put("workCount", workCount);
            guards.add(guard);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", guards);
        return result;
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
        if ("禁用".equals(status)) {
            // 保护检查全部通过后才隐藏展示作品；复用当前事务，联动失败时用户状态也必须回滚。
            sqlSessionTemplate.update("com.pg.mapper.AdminUserMapper.hideVisibleWorks",
                    Collections.singletonMap("uid", uid));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uid", uid);
        result.put("status", status);
        return result;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> delete(String uid, String adminUid, boolean cascade) {
        validateId(uid);
        // 自我操作优先于最后管理员规则，否则本人会错误收到 409 而非 403。
        if (uid.equals(adminUid)) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能删除自己");
        }
        List<String> admins = adminUserMapper.lockNormalAdmins();
        Map<String, Object> target = requireLockedUser(uid);
        protectLastAdmin(target, admins);
        if (!cascade) {
            if (adminUserMapper.countWorks(uid) > 0) {
                throw new BizException(ErrorCode.CONFLICT, "用户仍有作品，不能删除");
            }
            // 无作品不等于没有从属数据：comment/favorite/liketable/follow 对 user 是 RESTRICT 外键，
            // 只删 user 会直接撞外键报 500。这里复用同一批清理（作品集合为空，不会误删别人的作品数据）。
            List<String> noWorks = java.util.Collections.emptyList();
            adminUserMapper.deleteComments(uid);
            adminUserMapper.deleteFavorites(uid);
            adminUserMapper.deleteLikes(uid);
            adminUserMapper.deleteVotes(uid, noWorks);
            adminUserMapper.deleteWorkCategories(uid);
            adminUserMapper.deleteWorkEmbeddings(uid);
            adminUserMapper.deleteWorkExif(uid);
            adminUserMapper.deleteAudits(uid);
            adminUserMapper.deleteFollows(uid);
            adminUserMapper.deleteGifts(uid);
            adminUserMapper.deleteOrders(uid);
            adminUserMapper.delete(uid);
            return null;
        }
        // vote 删除触发器会更新 pwork；先锁定作品编号，避免删除语句同时读写 pwork 触发 MySQL 1442。
        List<String> workIds = adminUserMapper.lockWorkIds(uid);
        // §5.1：先清从属表再删作品，后续子查询仍需作品归属；任一步异常必须向外抛以回滚整笔。
        adminUserMapper.deleteComments(uid);
        adminUserMapper.deleteFavorites(uid);
        adminUserMapper.deleteLikes(uid);
        adminUserMapper.deleteVotes(uid, workIds);
        adminUserMapper.deleteWorkCategories(uid);
        adminUserMapper.deleteWorkEmbeddings(uid);
        adminUserMapper.deleteWorkExif(uid);
        adminUserMapper.deleteAudits(uid);
        adminUserMapper.deleteFollows(uid);
        long deletedWorks = adminUserMapper.deleteWorks(uid);
        // 订单与礼物已停用且无外键，依裁决只清理目标用户行，不扩大到其它用户。
        adminUserMapper.deleteGifts(uid);
        adminUserMapper.deleteOrders(uid);
        adminUserMapper.delete(uid);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uid", uid);
        result.put("deletedWorks", deletedWorks);
        return result;
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
