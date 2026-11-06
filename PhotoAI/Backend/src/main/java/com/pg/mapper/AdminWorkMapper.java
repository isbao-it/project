package com.pg.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 管理员作品只读投影；状态写入统一交给 AuditMapper。 */
@Mapper
public interface AdminWorkMapper {
    /** @param query 已验证的查询条件 @return 同条件总数 */
    Long count(Map<String, Object> query);

    /** @param query 已验证条件及分页偏移 @return 按时间倒序、编号升序的作品行 */
    List<Map<String, Object>> page(Map<String, Object> query);

    /** @param pid 作品编号 @return 任意状态作品投影，不存在为 null */
    Map<String, Object> detail(@Param("pid") String pid);

    /** @param pid 作品编号 @return 六项摄影参数，无记录为 null */
    Map<String, Object> exif(@Param("pid") String pid);
}
