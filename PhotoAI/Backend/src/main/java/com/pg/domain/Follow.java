package com.pg.domain;

import lombok.Getter;

import java.util.Date;

/** 关注关系写入对象；身份由服务端提供，不能直接作为响应。 */
@Getter
public class Follow {
    private final String fid;
    private final String uid;
    private final String tuid;
    private final Date ct;

    /**
     * 构造新关系及创建时间；重复关注时不覆盖已有记录。
     * @param fid 服务端生成的关系编号
     * @param uid 已验证的关注者
     * @param tuid 被关注者
     */
    public Follow(String fid, String uid, String tuid) {
        this.fid = fid;
        this.uid = uid;
        this.tuid = tuid;
        this.ct = new Date();
    }
}
