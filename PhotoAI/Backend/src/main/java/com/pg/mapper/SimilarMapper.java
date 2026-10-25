package com.pg.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 同款 SQL；源图和候选读取均不改变作品数据。 */
@Mapper
public interface SimilarMapper {
    /** @param pid 源作品编号；@return 展示源图编号、分类及必要 EXIF，不存在或非展示为 null。 */
    Map<String, Object> source(@Param("pid") String pid);

    /** @param query 已校验源图参数、两个阈值及条数上限；@return 过滤、排序、截断后的 Work 投影。 */
    List<Map<String, Object>> matches(Map<String, Object> query);
}
