package com.pg.service;

import java.util.Map;

/** 账号与资料服务；所有出口均为冻结字段投影，不返回持久化实体。 */
public interface ProfileService {
    /** @param openid 微信验证后的身份 @return 登录令牌、用户编号和是否首登；首次调用可能写库。 */
    Map<String, Object> login(String openid);

    /** @param uid 当前登录用户编号 @return 本人十字段兼容资料。 */
    Map<String, Object> info(String uid);

    /** @param uid 目标用户编号 @return 经权限校验的八字段旧资料，不存在时抛 NOT_FOUND。 */
    Map<String, Object> publicProfile(String uid);

    /** @param uid 本人编号 @param changes 候选资料补丁 @return 写库后的五字段本人资料。 */
    Map<String, Object> updateProfile(String uid, Map<String, Object> changes);
}
