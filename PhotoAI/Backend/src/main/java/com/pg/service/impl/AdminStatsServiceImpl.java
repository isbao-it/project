package com.pg.service.impl;

import com.pg.mapper.AdminStatsMapper;
import com.pg.service.AdminStatsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 在同一只读快照中构造概览，避免并发审核导致总数与分布不一致。 */
@Service
public class AdminStatsServiceImpl implements AdminStatsService {
    private static final List<String> STATUSES = Arrays.asList("pending", "visible", "hidden", "rejected");
    private static final List<String> RECENT_FIELDS = Arrays.asList("pid", "uid", "wtitle", "vurl",
            "uname", "pcname", "cwtime", "wstatus");
    private final AdminStatsMapper adminStatsMapper;

    /** @param adminStatsMapper 只读统计查询组件 */
    public AdminStatsServiceImpl(AdminStatsMapper adminStatsMapper) {
        this.adminStatsMapper = adminStatsMapper;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> stats() {
        // 一次请求只取一次日期；分别计算两个零点，避免跨午夜或夏令时造成区间漂移。
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        Timestamp start = Timestamp.valueOf(today.atStartOfDay(zone).toLocalDateTime());
        Timestamp end = Timestamp.valueOf(today.plusDays(1).atStartOfDay(zone).toLocalDateTime());
        Map<String, Object> counts = adminStatsMapper.statusCounts();
        Map<String, Object> statuses = new LinkedHashMap<>();
        for (String status : STATUSES) {
            statuses.put(status, ((Number) counts.get(status)).longValue());
        }
        List<Map<String, Object>> categories = adminStatsMapper.categoryCounts();
        categories.replaceAll(row -> {
            Map<String, Object> category = new LinkedHashMap<>();
            category.put("pcid", row.get("pcid"));
            category.put("pcname", row.get("pcname"));
            category.put("count", ((Number) row.get("count")).longValue());
            return category;
        });
        Map<String, Object> uncategorized = new LinkedHashMap<>();
        uncategorized.put("pcid", null);
        uncategorized.put("pcname", "未分类");
        uncategorized.put("count", adminStatsMapper.uncategorizedCount());
        categories.add(uncategorized);
        List<Map<String, Object>> recent = adminStatsMapper.recentPending();
        recent.replaceAll(row -> {
            Map<String, Object> work = new LinkedHashMap<>();
            for (String field : RECENT_FIELDS) {
                work.put(field, row.get(field));
            }
            return work;
        });
        // 出口只投影契约七键，nullable 分类及作品字段也必须保留。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pendingCount", statuses.get("pending"));
        result.put("todayApprovedCount", adminStatsMapper.todayApprovedCount(start, end));
        result.put("userCount", adminStatsMapper.userCount());
        result.put("workCount", ((Number) counts.get("workCount")).longValue());
        result.put("statusCounts", statuses);
        result.put("categoryCounts", categories);
        result.put("recentPending", recent);
        return result;
    }
}
