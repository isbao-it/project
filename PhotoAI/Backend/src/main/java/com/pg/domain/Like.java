package com.pg.domain;

import lombok.Getter;

/** 帖子点赞写入对象；类型由服务端固定，不接受客户端指定。 */
@Getter
public class Like {
    private final String lid;
    private final String uid;
    private final String targetid;
    private final String targettype = "帖子";

    /**
     * 构造一条帖子赞，数据库负责填写 likect。
     * @param lid 服务端生成的点赞编号
     * @param uid 已验证用户编号
     * @param targetid 作品编号
     */
    public Like(String lid, String uid, String targetid) {
        this.lid = lid;
        this.uid = uid;
        this.targetid = targetid;
    }
}
