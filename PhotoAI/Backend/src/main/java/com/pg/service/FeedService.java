package com.pg.service;

import java.util.Map;

/** 本人关注流查询契约，不增加作品浏览量。 */
public interface FeedService {
    /**
     * 只读取已关注作者的展示作品。
     * @param uid 已验证的本人编号
     * @param page 从一开始的页码
     * @param size 每页条数
     * @return 完整 Work 列表及过滤后的 Long 类型总数
     */
    Map<String, Object> following(String uid, int page, int size);
}
