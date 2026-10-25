package com.pg.service.impl;

import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.mapper.FeedMapper;
import com.pg.service.FeedService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 本人关注流只读查询；复用作品列表投影，避免字段随接口演进遗漏。 */
@Service
public class FeedServiceImpl implements FeedService {
    private static final int PAGE_SIZE_MAX = 50; // 冻结§1.1 的页长上限。
    private final FeedMapper feedMapper;
    private final WorkQueryServiceImpl workQueryService;

    /**
     * 固定关注流读取依赖。
     * @param feedMapper 已关注作者的展示作品查询 SQL
     * @param workQueryService 与公开作品列表共用的字段投影
     */
    public FeedServiceImpl(FeedMapper feedMapper, WorkQueryServiceImpl workQueryService) {
        this.feedMapper = feedMapper;
        this.workQueryService = workQueryService;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> following(String uid, int page, int size) {
        if (page < 1 || size < 1 || size > PAGE_SIZE_MAX) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "关注流分页参数不合法");
        }
        // 入口是已验证本人编号与分页；使用 long 位移避免大页码的 int 乘法溢出。
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("uid", uid);
        query.put("size", size);
        query.put("offset", ((long) page - 1) * size);
        // 同一只读事务内读取总数和本页，避免两次查询看见不同的关系或作品状态。
        Long total = feedMapper.count(query);
        List<Map<String, Object>> rows = feedMapper.page(query);
        // 出口复用 /pwork/list 的白名单与计数转换，不调用有浏览量副作用的详情。
        rows.replaceAll(row -> workQueryService.project(row, false));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        result.put("total", total);
        return result;
    }
}
