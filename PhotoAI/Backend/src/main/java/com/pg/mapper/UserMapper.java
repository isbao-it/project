package com.pg.mapper;

import com.pg.domain.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * @Entity com.pg.domain.User
 */
//@Mapper 注解被 MyBatis 框架识别为 Mapper 接口
@Mapper
//接口继承了 BaseMapper<User>，这是 MyBatis-Plus 提供的基础映射接口。
//通过泛型指定实体类 User，BaseMapper
//会自动将 User 类与数据库中的对应表建立映射关系，
//并默认提供一系列常用的 CRUD（增删改查）方法
public interface UserMapper extends BaseMapper<User> {
//    BaseMapper 的基础方法根据传入的动态参数映射查询用户列表。
//    使用 Map 传参适用于查询条件不固定、动态拼接的场景
    List<User> queryUser(Map<String,Object> param);

    /** 主页专用查询结果，继承资料字段并接收展示作品获赞总数，不用于写库。 */
    class HomeProfile extends User {
        private Long likedTotal;

        /** @return 该用户展示作品的获赞总数，无作品时为 0。 */
        public Long getLikedTotal() { return likedTotal; }

        /** @param likedTotal SQL 聚合的展示作品获赞总数，由 MyBatis 映射。 */
        public void setLikedTotal(Long likedTotal) { this.likedTotal = likedTotal; }
    }

    /** @param uid 目标用户编号；@return 安全资料列及展示作品获赞总数，不读凭据，不写库。 */
    HomeProfile homeProfile(@Param("uid") String uid);

    /** @param uid 目标用户；@param viewerUid 已验证请求者，可空；@return 统计和已计算的可见性；不存在时为 null。 */
    Map<String, Object> homeState(@Param("uid") String uid, @Param("viewerUid") String viewerUid);

    /** @param uid 目标用户；@return 仅展示作品的数量。 */
    Long worksCount(@Param("uid") String uid);

    /** @param uid 目标用户；@param offset 从零开始的偏移；@param size 页长；@return 共享 Work 投影的展示作品。 */
    List<Map<String, Object>> works(@Param("uid") String uid, @Param("offset") long offset,
                                   @Param("size") int size);

    /** @param uid 已验证本人；@param visFavorite 收藏开关；@param visLike 喜欢开关；@param visFollowing 关注开关；@param visFollowers 粉丝开关；@return 本人记录的更新行数，只写四个开关。 */
    int updatePrivacy(@Param("uid") String uid, @Param("visFavorite") boolean visFavorite,
                      @Param("visLike") boolean visLike, @Param("visFollowing") boolean visFollowing,
                      @Param("visFollowers") boolean visFollowers);

}




