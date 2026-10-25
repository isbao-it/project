package com.pg.service;

/** 文本安全唯一契约，作品与评论共用；通过正常返回，拒绝 422，不可用 503。 */
public interface MsgSecCheck {
    /**
     * 检查文本，不落业务行；
     * @param uid 已验证用户编号
     * @param content 待检查原文
     */
    void check(String uid, String content);
}
