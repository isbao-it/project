package com.pg.domain;

import lombok.Data;

/** 评论持久层投影；保存完整复合键，昵称和头像仅用于列表作者投影。 */
@Data
public class Comment {
    private String cid;
    private String pid;
    private String uid;
    private String content;
    private String createdAt;
    private String nickname;
    private String avatar;
}
