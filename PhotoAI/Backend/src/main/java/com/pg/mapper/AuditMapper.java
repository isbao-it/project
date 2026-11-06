package com.pg.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/** 审核专用数据访问，独立承担审核状态写入和日志追加。 */
@Mapper
public interface AuditMapper {
    /** @param pid 作品编号 @return 锁定后的状态与最近日志时间，不存在为 null */
    Map<String, Object> lockStatus(@Param("pid") String pid);

    /** @param pid 作品编号 @param current 锁定读得到的旧状态 @param status 目标状态 @return 非待审条件更新行数 */
    int updateNonPending(@Param("pid") String pid, @Param("current") String current,
                         @Param("status") String status);

    /** @return 固定待审核过滤后的总数 */
    Long countPending();

    /**
     * 读取待审核作品完整投影，不增加浏览数。
     * @param size 条数
     * @param offset 长整型偏移
     * @return 作品行列表
     */
    List<Map<String, Object>> pending(@Param("size") int size, @Param("offset") long offset);

    /**
     * 仅在仍待审核时修改状态，并持有行锁至服务事务结束。
     * @param pid 作品编号
     * @param status 目标状态
     * @return 更新行数，零表示不存在或已被审核
     */
    int updatePending(@Param("pid") String pid, @Param("status") String status);

    /** @param pid 作品编号 @return 作品是否存在，以当前锁定读区分冲突和不存在 */
    String findForUpdate(@Param("pid") String pid);

    /**
     * 追加审核日志；与状态更新共用服务事务。
     * @param audit 服务端生成的日志字段
     * @return 插入行数
     */
    int insertAudit(Map<String, Object> audit);
}
