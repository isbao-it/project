package com.pg.service.impl;

import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.mapper.AdminWorkMapper;
import com.pg.mapper.AuditMapper;
import com.pg.service.AdminWorkService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 管理员专用作品服务，不调用作者发布或公开详情流程。 */
@Service
public class AdminWorkServiceImpl implements AdminWorkService {
    private static final int PAGE_SIZE_DEFAULT = 10; // ADMIN §3 默认每页十条。
    private static final int PAGE_SIZE_MAX = 50; // ADMIN §3 每页上限。
    private static final int KEYWORD_LIMIT = 50; // ADMIN §3 按 Unicode 字符计长。
    private static final ZoneId AUDIT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<String> STATUSES = Arrays.asList("待审核", "展示", "隐藏", "已驳回");
    private static final List<String> FIELDS = Arrays.asList("pid", "uid", "wtitle", "wdesc", "vurl",
            "viewcount", "votecount", "wstatus", "cwtime", "likep", "uname", "avatar", "pcid", "pcname",
            "realLikeCount", "favoriteCount", "reason");
    private static final List<String> COUNTERS = Arrays.asList("viewcount", "votecount", "likep",
            "realLikeCount", "favoriteCount");
    private static final List<String> EXIF_FIELDS = Arrays.asList("focal", "aperture", "shutter", "iso", "lens", "camera");
    private final AdminWorkMapper adminWorkMapper;
    private final AuditMapper auditMapper;

    /** @param adminWorkMapper 只读作品投影 @param auditMapper 状态与审核日志写入组件 */
    public AdminWorkServiceImpl(AdminWorkMapper adminWorkMapper, AuditMapper auditMapper) {
        this.adminWorkMapper = adminWorkMapper;
        this.auditMapper = auditMapper;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(Map<String, String> parameters) {
        // 入口保留原始字符串，不能让框架把空串分页替换成默认值或悄悄接受旧别名。
        if (parameters.containsKey("pageNum") || parameters.containsKey("pageSize")) {
            throw invalid();
        }
        String status = parameters.get("status");
        String keyword = parameters.get("keyword");
        String category = parameters.get("category");
        if ((status != null && !STATUSES.contains(status))
                || (keyword != null && keyword.codePointCount(0, keyword.length()) > KEYWORD_LIMIT)) {
            throw invalid();
        }
        if (category != null) {
            validateId(category);
        }
        int page = pageNumber(parameters.get("page"), 1);
        int size = pageNumber(parameters.get("size"), PAGE_SIZE_DEFAULT);
        if (page < 1 || size < 1 || size > PAGE_SIZE_MAX) {
            throw invalid();
        }
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("status", status);
        query.put("keyword", keyword);
        query.put("category", category);
        query.put("size", size);
        query.put("offset", ((long) page - 1) * size);
        Long total = adminWorkMapper.count(query);
        List<Map<String, Object>> rows = adminWorkMapper.page(query);
        rows.replaceAll(this::project);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        result.put("total", total);
        return result;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(String pid) {
        validateId(pid);
        Map<String, Object> row = adminWorkMapper.detail(pid);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "作品不存在");
        }
        Map<String, Object> work = project(row);
        Map<String, Object> storedExif = adminWorkMapper.exif(pid);
        Map<String, Object> exif = new LinkedHashMap<>();
        for (String field : EXIF_FIELDS) {
            exif.put(field, storedExif == null ? null : storedExif.get(field));
        }
        Map<String, Object> author = new LinkedHashMap<>();
        author.put("uid", work.get("uid"));
        author.put("nickname", work.get("uname"));
        author.put("avatar", work.get("avatar"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("work", work);
        result.put("exif", exif);
        result.put("author", author);
        return result;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public Map<String, Object> changeStatus(String pid, Object status, String adminUid) {
        validateId(pid);
        if (!"展示".equals(status) && !"隐藏".equals(status)) {
            throw invalid();
        }
        // 先锁作品再决定迁移；后到的恢复请求重读胜者状态，按展示幂等而不追加第二条 pass。
        Map<String, Object> locked = auditMapper.lockStatus(pid);
        if (locked == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "作品不存在");
        }
        String current = (String) locked.get("wstatus");
        if ("待审核".equals(current) || ("已驳回".equals(current) && "隐藏".equals(status))) {
            throw new BizException(ErrorCode.CONFLICT, "当前作品状态不允许该操作");
        }
        if (!status.equals(current)) {
            if (auditMapper.updateNonPending(pid, current, (String) status) != 1) {
                throw new BizException(ErrorCode.CONFLICT, "作品状态已改变");
            }
            if ("已驳回".equals(current)) {
                LocalDateTime time = LocalDateTime.now(AUDIT_ZONE).withNano(0);
                String latestText = (String) locked.get("latestAuditTime");
                LocalDateTime latest = latestText == null ? null : LocalDateTime.parse(latestText);
                // DATETIME 仅存秒，UUID 无时间顺序；同秒必须推进一秒，旧 reject 才不会仍是最近理由。
                if (latest != null && !time.isAfter(latest)) {
                    time = latest.plusSeconds(1);
                }
                Map<String, Object> audit = new LinkedHashMap<>();
                audit.put("aid", UUID.randomUUID().toString());
                audit.put("pid", pid);
                audit.put("act", "pass");
                audit.put("reason", null);
                audit.put("uadmin", adminUid);
                audit.put("ct", Timestamp.valueOf(time));
                if (auditMapper.insertAudit(audit) != 1) {
                    throw new BizException(ErrorCode.INTERNAL_ERROR, "审核日志写入失败");
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", pid);
        result.put("status", status);
        return result;
    }

    private Map<String, Object> project(Map<String, Object> row) {
        // 出口精确十七键；MyBatis 忽略的空值补回，所有计数统一为 Long。
        Map<String, Object> work = new LinkedHashMap<>();
        for (String field : FIELDS) {
            Object value = row.get(field);
            if (COUNTERS.contains(field)) {
                value = value == null ? 0L : ((Number) value).longValue();
            }
            work.put(field, value);
        }
        return work;
    }

    private int pageNumber(String text, int defaultValue) {
        if (text == null) {
            return defaultValue;
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

    private void validateId(String pid) {
        if (pid == null || !pid.matches("[A-Za-z0-9_-]{1,36}")) {
            throw invalid();
        }
    }

    private BizException invalid() {
        return new BizException(ErrorCode.INVALID_REQUEST, "作品管理参数不合法");
    }
}
