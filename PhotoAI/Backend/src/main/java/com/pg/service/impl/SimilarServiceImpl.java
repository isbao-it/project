package com.pg.service.impl;

import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.mapper.SimilarMapper;
import com.pg.service.SimilarService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 同款只读查询；缺必要参数直接返回空，绝不补热门作品。 */
@Service
public class SimilarServiceImpl implements SimilarService {
    private static final int LIMIT_MAX = 20; // 冻结§1.6/U6：超过上限必须拒绝，不能静默截断。
    private static final int APERTURE_STOPS_MAX = 1; // U6 的 LOG2 光圈档差上限，含边界。
    private static final BigDecimal FOCAL_RELATIVE_MAX = new BigDecimal("0.20"); // U6：相对源焦距的差。
    private static final List<String> WORK_FIELDS = Arrays.asList("pid", "uid", "wtitle", "wdesc", "vurl",
            "viewcount", "votecount", "wstatus", "cwtime", "likep", "uname", "avatar", "pcname", "realLikeCount");
    private final SimilarMapper similarMapper;

    /** @param similarMapper 无浏览副作用的源图及候选查询 SQL。 */
    public SimilarServiceImpl(SimilarMapper similarMapper) {
        this.similarMapper = similarMapper;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> similar(String pid, int limit) {
        if (pid == null || !pid.matches("[A-Za-z0-9_-]{1,36}") || limit < 1 || limit > LIMIT_MAX) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "同款查询参数不合法");
        }
        // 入口只读取展示源图；存在性先于 EXIF 判定，隐藏作品不能借缺参数得到成功响应。
        Map<String, Object> source = similarMapper.source(pid);
        if (source == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "作品不存在");
        }
        List<Map<String, Object>> rows = Collections.emptyList();
        if (source.get("pcid") != null && positive(source.get("focal")) && positive(source.get("aperture"))) {
            Map<String, Object> query = new LinkedHashMap<>(source);
            query.put("limit", limit);
            query.put("apertureStopsMax", APERTURE_STOPS_MAX);
            query.put("focalRelativeMax", FOCAL_RELATIVE_MAX);
            rows = similarMapper.matches(query);
            rows.replaceAll(this::project);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        return result;
    }

    private boolean positive(Object value) {
        return value instanceof Number && ((Number) value).doubleValue() > 0;
    }

    private Map<String, Object> project(Map<String, Object> row) {
        // 出口只含完整 Work；保留历史无作者的 null 键，不把源图参数或排序字段交给客户端。
        Map<String, Object> work = new LinkedHashMap<>();
        for (String field : WORK_FIELDS) {
            work.put(field, row.get(field));
        }
        return work;
    }
}
