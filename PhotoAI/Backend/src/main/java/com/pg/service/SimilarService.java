package com.pg.service;

import java.util.Map;

/** 以同分类、光圈和焦距筛选同款的只读契约。 */
public interface SimilarService {
    /**
     * 匹配展示作品；源缺必要参数或分类时返回空，不补热门。
     * @param pid 源作品编号
     * @param limit 返回条数，范围一至二十
     * @return 仅含 list 的完整 Work 投影
     */
    Map<String, Object> similar(String pid, int limit);
}
