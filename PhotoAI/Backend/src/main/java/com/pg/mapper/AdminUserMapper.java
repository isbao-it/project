package com.pg.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/** 用户管理独立投影及写入，保护检查使用同一事务中的当前锁定读。 */
@Mapper
public interface AdminUserMapper {
    /** @return 同一只读快照中的正常管理员数 */
    Long countNormalAdmins();

    /** @param uids 本页已校验的用户编号 @return 批量用户状态及任意状态作品数量，仅供内部保护判断 */
    List<Map<String, Object>> guardTargets(@Param("uids") List<String> uids);

    /** @param keyword 昵称片段或 null @return 匹配用户总数 */
    Long count(@Param("keyword") String keyword);

    /**
     * 查询八字段用户投影。
     * @param keyword 昵称片段或 null
     * @param size 条数
     * @param offset 长整型偏移
     * @return 不包含密码和微信身份的用户行
     */
    List<Map<String, Object>> page(@Param("keyword") String keyword, @Param("size") int size,
                                   @Param("offset") long offset);

    /** @return 按 uid 稳定顺序锁定后重读的正常管理员编号集合 */
    List<String> lockNormalAdmins();

    /** @param uid 目标用户编号 @return 锁定后的 uid/role/status 或 null */
    Map<String, Object> findForUpdate(@Param("uid") String uid);

    /** @param uid 目标用户编号 @return 任意状态作品总数 */
    Long countWorks(@Param("uid") String uid);

    /** 锁定目标用户所有状态的作品，供触发器相关清理使用。 @param uid 目标编号 @return 作品编号列表 */
    List<String> lockWorkIds(@Param("uid") String uid);

    /** @param uid 目标编号 @param status 已校验状态 @return 更新行数，仅写 status */
    int updateStatus(@Param("uid") String uid, @Param("status") String status);

    /** 删除目标用户发表或其作品收到的评论。 @param uid 目标编号 @return 实际删除行数 */
    int deleteComments(@Param("uid") String uid);

    /** 删除目标用户发起或其作品收到的收藏。 @param uid 目标编号 @return 实际删除行数 */
    int deleteFavorites(@Param("uid") String uid);

    /** 删除目标用户发起的点赞及其作品收到的帖子点赞。 @param uid 目标编号 @return 实际删除行数 */
    int deleteLikes(@Param("uid") String uid);

    /** 删除目标用户发起或其作品收到的投票。 @param uid 目标编号 @param workIds 已锁定作品编号 @return 实际删除行数 */
    int deleteVotes(@Param("uid") String uid, @Param("workIds") List<String> workIds);

    /** 删除目标用户作品的分类关联。 @param uid 目标编号 @return 实际删除行数 */
    int deleteWorkCategories(@Param("uid") String uid);

    /** 删除目标用户作品的向量数据。 @param uid 目标编号 @return 实际删除行数 */
    int deleteWorkEmbeddings(@Param("uid") String uid);

    /** 删除目标用户作品的拍摄参数。 @param uid 目标编号 @return 实际删除行数 */
    int deleteWorkExif(@Param("uid") String uid);

    /** 删除目标用户作品或由其审核的记录。 @param uid 目标编号 @return 实际删除行数 */
    int deleteAudits(@Param("uid") String uid);

    /** 删除目标用户双向关注关系。 @param uid 目标编号 @return 实际删除行数 */
    int deleteFollows(@Param("uid") String uid);

    /** 删除目标用户所有状态的作品。 @param uid 目标编号 @return 实际删除行数 */
    int deleteWorks(@Param("uid") String uid);

    /** 删除目标用户的历史礼物数据。 @param uid 目标编号 @return 实际删除行数 */
    int deleteGifts(@Param("uid") String uid);

    /** 删除目标用户的历史订单数据。 @param uid 目标编号 @return 实际删除行数 */
    int deleteOrders(@Param("uid") String uid);

    /** @param uid 目标用户编号 @return 删除行数，不清理其它关联 */
    int delete(@Param("uid") String uid);
}
