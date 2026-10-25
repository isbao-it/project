package com.pg.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/** 查询作品的完整冻结字段，计数始终由 Long 承接。 */
@Mapper
public interface WorkQueryMapper {
    /**
     * 按过滤条件计数；
     * @param query 服务端条件
     * @return 总行数
     */
    Long count(Map<String, Object> query);
    /**
     * 查询一页；
     * @param query 服务端条件与偏移
     * @return 完整作品投影
     */
    List<Map<String, Object>> page(Map<String, Object> query);
    /**
     * 查询展示作品或作者本人的作品；
     * @param pid 作品编号
     * @param viewerUid 已验证的访问者编号，匿名时为 null
     * @return 作品投影或 null
     */
    Map<String, Object> displayed(@Param("pid") String pid, @Param("viewerUid") String viewerUid);
    /**
     * 按相同可见性条件递增并锁住作品行至事务结束；
     * @param pid 作品编号
     * @param viewerUid 已验证的访问者编号，匿名时为 null
     * @return 更新行数
     */
    int incrementView(@Param("pid") String pid, @Param("viewerUid") String viewerUid);
    /**
     * 仅供文本安全内部调用，不进入任何对外投影；
     * @param uid 用户编号
     * @return 微信身份或 null
     */
    String securityIdentity(@Param("uid") String uid);
}
