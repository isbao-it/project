package com.pg.service;

import java.util.Map;

/** 帖子点赞契约；记录和计数在同一个 Java 事务内维护。 */
public interface LikeService {
    /**
     * 查询本人点赞过的展示作品，不修改点赞记录和计数。
     * @param uid 已验证用户编号
     * @param page 页码，从 1 开始
     * @param size 页长，范围 1–50
     * @return list 为作品列表，total 为相同条件下的总数
     */
    Map<String, Object> mine(String uid, int page, int size);

    /**
     * 幂等增加帖子赞，实际插入才递增 likep。
     * @param uid 已验证用户编号
     * @param pid 作品编号
     * @return liked=true 及 Long 类型帖子赞总数
     */
    Map<String, Object> like(String uid, String pid);

    /**
     * 幂等取消本人帖子赞，实际删除才递减 likep，最低为零。
     * @param uid 已验证用户编号
     * @param pid 作品编号
     * @return liked=false 及 Long 类型帖子赞总数
     */
    Map<String, Object> unlike(String uid, String pid);
}
