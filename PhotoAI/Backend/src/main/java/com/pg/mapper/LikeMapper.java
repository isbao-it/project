package com.pg.mapper;

import com.pg.domain.Like;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 帖子赞 SQL；仅实际变更记录时由服务调用计数更新。 */
@Mapper
public interface LikeMapper {
    /** @param uid 目标用户；@param viewerUid 已验证请求者，可空；@return 满足主页可见性与展示条件的帖子赞总数。 */
    Long homeCount(@Param("uid") String uid, @Param("viewerUid") String viewerUid);

    /** @param uid 目标用户；@param viewerUid 已验证请求者，可空；@param offset 从零开始的偏移；@param size 页长；@return 可见帖子赞的共享 Work 投影。 */
    List<Map<String, Object>> homePage(@Param("uid") String uid, @Param("viewerUid") String viewerUid,
                                      @Param("offset") long offset, @Param("size") int size);

    /**
     * 按点赞时间降序、点赞编号升序读取本人的展示作品。
     * @param uid 已验证用户编号
     * @param size 页长
     * @param offset 从零开始的偏移量
     * @return 与作品列表相同投影的当前页
     */
    List<Map<String, Object>> pageByUser(@Param("uid") String uid,
                                       @Param("size") int size,
                                       @Param("offset") long offset);

    /**
     * 统计本人点赞过的展示作品，仅包含帖子赞。
     * @param uid 已验证用户编号
     * @return 与分页查询条件相同的总数
     */
    Long countByUser(@Param("uid") String uid);

    /** @param pid 作品编号；@return 是否为展示作品。 */
    boolean displayed(@Param("pid") String pid);

    /** @param like 服务端构造的帖子赞；@return 实际插入行数。 */
    int insert(Like like);

    /**
     * 只删除本人的帖子赞。
     * @param uid 用户编号
     * @param pid 作品编号
     * @return 实际删除行数
     */
    int delete(@Param("uid") String uid, @Param("pid") String pid);

    /**
     * 唯一键冲突后核实确实已赞，避免吞掉其它键冲突。
     * @param uid 用户编号
     * @param pid 作品编号
     * @return 本人帖子赞记录数
     */
    Long ownedCount(@Param("uid") String uid, @Param("pid") String pid);

    /** @param pid 作品编号；@return 该作品帖子赞总数，排除其它类型。 */
    Long count(@Param("pid") String pid);

    /** @param pid 作品编号；@return 原子递增的行数，非展示作品为零。 */
    int increment(@Param("pid") String pid);

    /** @param pid 作品编号；@return 原子递减的匹配行数，非展示作品为零；结果不为负。 */
    int decrement(@Param("pid") String pid);
}
