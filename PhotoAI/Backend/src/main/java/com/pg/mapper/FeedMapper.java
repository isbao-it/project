package com.pg.mapper;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/** 独立查询关注流；总数和列表共用可见性过滤。 */
@Mapper
public interface FeedMapper {
    /** @param query 本人编号；@return 已关注作者的展示作品总数。 */
    Long count(Map<String, Object> query);

    /** @param query 本人编号及分页；@return 按发布时间降序、作品编号升序的 Work 投影。 */
    List<Map<String, Object>> page(Map<String, Object> query);
}
