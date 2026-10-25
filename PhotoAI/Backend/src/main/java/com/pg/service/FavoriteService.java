package com.pg.service;

import java.util.Map;

/** 展示作品的幂等收藏与本人列表契约。 */
public interface FavoriteService {
    /**
     * 新增收藏，唯一键冲突视为已经收藏。
     * @param uid 已验证用户编号
     * @param pid 作品编号
     * @return favorited=true 和 Long 类型作品收藏总数
     */
    Map<String, Object> favorite(String uid, String pid);

    /**
     * 只删除本人收藏，重复取消仍成功。
     * @param uid 已验证用户编号
     * @param pid 作品编号
     * @return favorited=false 和 Long 类型作品收藏总数
     */
    Map<String, Object> unfavorite(String uid, String pid);

    /**
     * 只读本人仍展示的收藏，不清理历史非展示关系。
     * @param uid 已验证用户编号
     * @param page 从一开始的页码
     * @param size 每页条数
     * @return 完整 Work 列表及相同过滤条件下的 Long 总数
     */
    Map<String, Object> mine(String uid, int page, int size);
}
