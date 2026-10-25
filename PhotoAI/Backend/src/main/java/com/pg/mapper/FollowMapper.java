package com.pg.mapper;

import com.pg.domain.Follow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 关注关系持久层；列表只投影另一方的四字段资料。 */
@Mapper
public interface FollowMapper {
    /** @param tuid 目标用户编号；@return 目标是否存在。 */
    boolean userExists(@Param("tuid") String tuid);

    /** @param follow 服务端生成的关系；@return 实际写入行数，唯一键冲突由本业务处理。 */
    int insert(Follow follow);

    /**
     * 删除本人到指定目标的关系。
     * @param uid 关注者
     * @param tuid 被关注者
     * @return 实际删除行数
     */
    int delete(@Param("uid") String uid, @Param("tuid") String tuid);

    /**
     * 删除指定粉丝到本人的关注关系。
     * @param followerUid 关注本人的粉丝编号
     * @param ownerUid 已验证的本人编号
     * @return 实际删除行数，零表示关系不存在
     */
    int removeFollower(@Param("followerUid") String followerUid, @Param("ownerUid") String ownerUid);

    /**
     * 核实唯一键冲突是否属于同一关系。
     * @param uid 关注者
     * @param tuid 被关注者
     * @return 该关系的真实行数
     */
    Long ownedCount(@Param("uid") String uid, @Param("tuid") String tuid);

    /** @param tuid 被关注者；@return 该目标的全部粉丝数。 */
    Long followersCount(@Param("tuid") String tuid);

    /** @param query 本人编号及查询方向；@return 该方向的全部用户数。 */
    Long count(Map<String, Object> query);

    /** @param query 本人编号、方向及分页；@return 四字段资料列表，按关系时间降序和编号升序。 */
    List<Map<String, Object>> page(Map<String, Object> query);

    /** @param query 路径目标编号及查询方向；@return 与他人关系列表共享条件的总数，调用前须校验可见性。 */
    Long homeCount(Map<String, Object> query);

    /** @param query 路径目标编号、方向及分页；@return 与本人列表共享投影和排序的四字段资料，调用前须校验可见性。 */
    List<Map<String, Object>> homePage(Map<String, Object> query);
}
