package com.pg.mapper;

import com.pg.domain.Favorite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 收藏 SQL；写入不改作品计数，读取只投影公开字段。 */
@Mapper
public interface FavoriteMapper {
    /** @param uid 目标用户；@param viewerUid 已验证请求者，可空；@return 满足主页可见性与展示条件的收藏总数。 */
    Long homeCount(@Param("uid") String uid, @Param("viewerUid") String viewerUid);

    /** @param uid 目标用户；@param viewerUid 已验证请求者，可空；@param offset 从零开始的偏移；@param size 页长；@return 可见收藏的共享 Work 投影。 */
    List<Map<String, Object>> homePage(@Param("uid") String uid, @Param("viewerUid") String viewerUid,
                                      @Param("offset") long offset, @Param("size") int size);

    /**
     * 判断某个用户是否收藏了某作品，不写库。
     * @param uid 当前请求者编号
     * @param pid 作品编号
     * @return 是否存在该用户与作品的收藏关系
     */
    boolean exists(@Param("uid") String uid, @Param("pid") String pid);

    /** @param pid 作品编号；@return 是否存在展示作品，不增加浏览量。 */
    boolean displayed(@Param("pid") String pid);

    /** @param favorite 服务端构造的收藏；@return 实际插入行数，唯一键冲突由服务处理。 */
    int insert(Favorite favorite);

    /**
     * 只删除本人对应作品的收藏。
     * @param uid 已验证用户编号
     * @param pid 作品编号
     * @return 实际删除行数
     */
    int delete(@Param("uid") String uid, @Param("pid") String pid);

    /**
     * 冲突后确认同一用户已收藏，避免吞掉其它键冲突。
     * @param uid 已验证用户编号
     * @param pid 作品编号
     * @return 本人对应收藏行数
     */
    Long ownedCount(@Param("uid") String uid, @Param("pid") String pid);

    /** @param pid 作品编号；@return 所有用户对该作品的收藏总数。 */
    Long count(@Param("pid") String pid);

    /** @param uid 已验证用户编号；@return 本人展示作品收藏总数。 */
    Long mineCount(@Param("uid") String uid);

    /**
     * 按收藏时间降序、收藏编号升序读取展示作品。
     * @param uid 已验证用户编号
     * @param offset 从零开始的偏移，使用 long 避免页码乘法溢出
     * @param size 每页条数
     * @return 完整 Work 字段投影
     */
    List<Map<String, Object>> mine(@Param("uid") String uid, @Param("offset") long offset,
                                   @Param("size") int size);
}
