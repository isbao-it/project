package com.pg.mapper;

import com.pg.domain.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

/** 账号专用 SQL；避免把资料补丁扩展成对整个用户实体的写回。 */
@Mapper
public interface ProfileMapper {
    /** @param uid 用户编号 @return 包含资料的持久化用户，不存在为 null。 */
    User findById(@Param("uid") String uid);

    /** @param openid 微信验证后的身份 @return 对应账号，不存在为 null。 */
    User findByOpenid(@Param("openid") String openid);

    /** @param user 服务端生成的默认微信账号 @return 插入行数；唯一键竞争由调用者收敛。 */
    int insertAccount(User user);

    /** @param uid 本人编号 @param changes 经过校验的白名单补丁 @return 更新行数。 */
    int updateProfile(@Param("uid") String uid, @Param("changes") Map<String, Object> changes);
}
