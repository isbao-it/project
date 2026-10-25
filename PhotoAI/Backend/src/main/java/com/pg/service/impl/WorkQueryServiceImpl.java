package com.pg.service.impl;

import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.domain.WorkExif;
import com.pg.mapper.FavoriteMapper;
import com.pg.mapper.WorkExifMapper;
import com.pg.mapper.WorkQueryMapper;
import com.pg.service.WorkQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 作品读取入口；显式补齐 null 键并保持公开可见性。 */
@Service
public class WorkQueryServiceImpl implements WorkQueryService {
    private static final int PAGE_SIZE_MAX = 50; // 冻结§1.1 分页上限。
    private static final List<String> FIELDS = Arrays.asList("pid", "uid", "wtitle", "wdesc", "vurl",
            "viewcount", "votecount", "wstatus", "cwtime", "likep", "uname", "avatar", "pcid", "pcname", "realLikeCount", "favoriteCount");
    private static final List<String> COUNTERS = Arrays.asList("viewcount", "votecount", "likep", "realLikeCount", "favoriteCount");
    private final WorkQueryMapper workQueryMapper;
    private final WorkExifMapper workExifMapper;
    private final FavoriteMapper favoriteMapper;

    /**
     * 固定读取依赖；
     * @param workQueryMapper 作品投影
     * @param workExifMapper 六项参数
     * @param favoriteMapper 当前请求者的收藏状态查询
     */
    public WorkQueryServiceImpl(WorkQueryMapper workQueryMapper, WorkExifMapper workExifMapper,
                                FavoriteMapper favoriteMapper) {
        this.workQueryMapper = workQueryMapper;
        this.workExifMapper = workExifMapper;
        this.favoriteMapper = favoriteMapper;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public Map<String, Object> detail(String pid, String viewerUid) {
        validateId(pid);
        // UPDATE 先取得行锁；读完增加后的值才提交，避免并发请求都拿到同一最终计数。
        if (workQueryMapper.incrementView(pid, viewerUid) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "作品不存在");
        }
        Map<String, Object> work = project(workQueryMapper.displayed(pid, viewerUid), false);
        // 收藏状态只对已登录请求者有意义；匿名一律 false，不泄漏他人状态。
        boolean favorited = false;
        if (viewerUid != null) {
            favorited = favoriteMapper.exists(viewerUid, pid);
        }
        work.put("favorited", favorited);
        Map<String, Object> storedExif = workExifMapper.find(pid);
        Map<String, Object> exif = null;
        if (storedExif != null) {
            exif = WorkExif.empty();
            for (String field : exif.keySet()) {
                exif.put(field, storedExif.get(field));
            }
        }
        Map<String, Object> author = null;
        if (work.get("uid") != null) {
            author = new LinkedHashMap<>();
            author.put("uid", work.get("uid"));
            author.put("nickname", work.get("uname"));
            author.put("avatar", work.get("avatar"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("work", work);
        result.put("exif", exif);
        result.put("author", author);
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> list(String tab, String category, int page, int size, String viewerUid) {
        if (!Arrays.asList("recommend", "latest", "hot").contains(tab)) {
            throw invalid();
        }
        if (category != null) {
            validateId(category);
        }
        Map<String, Object> query = query(page, size);
        query.put("tab", tab);
        query.put("category", category);
        query.put("viewerUid", viewerUid);
        return readPage(query, false);
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> mine(String uid, String status, int page, int size) {
        if (status != null && !Arrays.asList("待审核", "展示", "隐藏", "已驳回").contains(status)) {
            throw invalid();
        }
        Map<String, Object> query = query(page, size);
        query.put("uid", uid);
        query.put("status", status);
        return readPage(query, true);
    }

    private Map<String, Object> query(int page, int size) {
        if (page < 1 || size < 1 || size > PAGE_SIZE_MAX) {
            throw invalid();
        }
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("uid", null);
        query.put("status", null);
        query.put("category", null);
        query.put("tab", "latest");
        query.put("size", size);
        query.put("offset", ((long) page - 1) * size);
        return query;
    }

    private Map<String, Object> readPage(Map<String, Object> query, boolean mine) {
        Long total = workQueryMapper.count(query);
        List<Map<String, Object>> rows = workQueryMapper.page(query);
        rows.replaceAll(row -> project(row, mine));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        result.put("total", total);
        return result;
    }

    /**
     * 统一作品列表与关注流的字段，补齐空键并将计数转换为 Long，不写库。
     * @param row Mapper 返回的作品列
     * @param mine 是否追加本人列表的审核原因
     * @return 仅包含白名单字段的作品对象
     */
    public Map<String, Object> project(Map<String, Object> row, boolean mine) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : FIELDS) {
            Object value = row.get(field);
            if (COUNTERS.contains(field)) {
                value = value == null ? 0L : ((Number) value).longValue();
            }
            result.put(field, value);
        }
        if (mine) {
            result.put("reason", row.get("reason"));
        }
        return result;
    }

    private void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,36}")) {
            throw invalid();
        }
    }

    private BizException invalid() {
        return new BizException(ErrorCode.INVALID_REQUEST, "作品查询参数不合法");
    }
}
