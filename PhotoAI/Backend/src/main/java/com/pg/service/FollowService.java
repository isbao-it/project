package com.pg.service;

import java.util.Map;

/** 本人关注关系契约；所有响应只包含冻结字段。 */
public interface FollowService {
    /**
     * 幂等写入关注关系。
     * @param uid 已验证的关注者
     * @param tuid 被关注者
     * @return following=true 及目标的 Long 类型粉丝数
     */
    Map<String, Object> follow(String uid, String tuid);

    /**
     * 幂等删除本人的指定关注关系。
     * @param uid 已验证的关注者
     * @param tuid 被关注者
     * @return following=false 及目标的 Long 类型粉丝数
     */
    Map<String, Object> unfollow(String uid, String tuid);

    /**
     * 删除指定粉丝对本人的关注关系；关系不存在时拒绝。
     * @param ownerUid 已验证的本人编号
     * @param followerUid 待移除的粉丝编号
     * @return removed=true 及本人删除后的 Long 类型 followersCount
     */
    Map<String, Object> removeFollower(String ownerUid, String followerUid);

    /**
     * 分页读取本人关注的用户，不写库。
     * @param uid 已验证的本人编号
     * @param page 从一开始的页码
     * @param size 每页条数
     * @return 四字段用户列表及 Long 类型总数
     */
    Map<String, Object> following(String uid, int page, int size);

    /**
     * 分页读取本人的粉丝，不写库。
     * @param uid 已验证的本人编号
     * @param page 从一开始的页码
     * @param size 每页条数
     * @return 四字段用户列表及 Long 类型总数
     */
    Map<String, Object> followers(String uid, int page, int size);
}
