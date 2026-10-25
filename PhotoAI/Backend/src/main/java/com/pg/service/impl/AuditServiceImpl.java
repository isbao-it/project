package com.pg.service.impl;

import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.mapper.AuditMapper;
import com.pg.service.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 审核条件更新与日志追加同事务，日志失败时自动回滚作品状态。 */
@Service
public class AuditServiceImpl implements AuditService {
    private static final int PAGE_SIZE_MAX = 50; // 冻结 §1.1：每页最多五十条。
    private static final int REASON_LIMIT = 200; // 冻结 §1.4：理由按 Unicode 字符数计长。
    private static final ZoneId AUDIT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<String> FIELDS = Arrays.asList("pid", "uid", "wtitle", "wdesc", "vurl",
            "viewcount", "votecount", "wstatus", "cwtime", "likep", "uname", "avatar", "pcname", "realLikeCount");
    private static final List<String> COUNTERS = Arrays.asList("viewcount", "votecount", "likep", "realLikeCount");
    private final AuditMapper auditMapper;

    /** @param auditMapper 只处理审核的数据访问组件 */
    public AuditServiceImpl(AuditMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> pending(int page, int size) {
        if (page < 1 || size < 1 || size > PAGE_SIZE_MAX) {
            throw invalid();
        }
        Long total = auditMapper.countPending();
        List<Map<String, Object>> rows = auditMapper.pending(size, ((long) page - 1) * size);
        // 出口只投影冻结十四键；MyBatis 省略的空值补回，计数统一为 Long。
        rows.replaceAll(row -> {
            Map<String, Object> work = new LinkedHashMap<>();
            for (String field : FIELDS) {
                Object value = row.get(field);
                if (COUNTERS.contains(field)) {
                    value = value == null ? 0L : ((Number) value).longValue();
                }
                work.put(field, value);
            }
            return work;
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        result.put("total", total);
        return result;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public Map<String, Object> approve(String pid, String adminUid) {
        return decide(pid, "展示", "pass", null, adminUid);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public Map<String, Object> reject(String pid, Object reason, String adminUid) {
        if (!(reason instanceof String)) {
            throw invalid();
        }
        String text = (String) reason;
        if (text.codePointCount(0, text.length()) > REASON_LIMIT
                || text.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            throw invalid();
        }
        return decide(pid, "已驳回", "reject", text, adminUid);
    }

    private Map<String, Object> decide(String pid, String status, String act, String reason, String adminUid) {
        if (pid == null || !pid.matches("[A-Za-z0-9_-]{1,36}")) {
            throw invalid();
        }
        // 条件 UPDATE 是唯一胜者判据；不能先查状态再覆盖并发审核结果。
        if (auditMapper.updatePending(pid, status) == 0) {
            if (auditMapper.findForUpdate(pid) == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "作品不存在");
            }
            throw new BizException(ErrorCode.CONFLICT, "作品已不处于待审核状态");
        }
        // 入口只有服务端状态、已验证管理员编号和校验后的原文；不消费客户端操作者字段。
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("aid", UUID.randomUUID().toString());
        audit.put("pid", pid);
        audit.put("act", act);
        audit.put("reason", reason);
        audit.put("uadmin", adminUid);
        audit.put("ct", Timestamp.valueOf(LocalDateTime.now(AUDIT_ZONE)));
        auditMapper.insertAudit(audit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", pid);
        result.put("status", status);
        return result;
    }

    private BizException invalid() {
        return new BizException(ErrorCode.INVALID_REQUEST, "审核参数不合法");
    }
}
