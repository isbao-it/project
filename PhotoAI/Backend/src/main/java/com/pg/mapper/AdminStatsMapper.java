package com.pg.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** 概览专用只读聚合，不修改作品和审计数据。 */
@Mapper
public interface AdminStatsMapper {
    /** @return 四状态数量与作品总数 */
    Map<String, Object> statusCounts();

    /** @return 包含禁用用户的总人数 */
    Long userCount();

    /** @param start 当日零点（含） @param end 次日零点（不含） @return 去重通过作品数 */
    Long todayApprovedCount(@Param("start") Timestamp start, @Param("end") Timestamp end);

    /** @return 按字典顺序排列、包含零件分类的分布 */
    List<Map<String, Object>> categoryCounts();

    /** @return 分类为空或无法关联字典的作品数 */
    Long uncategorizedCount();

    /** @return 按提交时间倒序、编号升序排列的最近三件待审核作品 */
    List<Map<String, Object>> recentPending();
}
