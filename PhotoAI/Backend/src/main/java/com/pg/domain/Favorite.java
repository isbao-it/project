package com.pg.domain;

import lombok.Getter;

import java.time.LocalDateTime;

/** 收藏持久化对象；身份由服务端设置，创建时间由数据库默认值填写。 */
@Getter
public class Favorite {
    private final String fid;
    private final String uid;
    private final String pid;
    private LocalDateTime ct;

    /**
     * 构造待插入收藏，不接受客户端提供身份或创建时间。
     * @param fid 服务端生成的收藏编号
     * @param uid 已验证用户编号
     * @param pid 展示作品编号
     */
    public Favorite(String fid, String uid, String pid) {
        this.fid = fid;
        this.uid = uid;
        this.pid = pid;
    }
}
