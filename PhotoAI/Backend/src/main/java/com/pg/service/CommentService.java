package com.pg.service;

import java.util.Map;

/** 评论业务契约；响应只投影冻结字段。 */
public interface CommentService {
    /**
     * 读取展示作品的评论，按创建时间倒序分页。
     * @param pid 作品编号
     * @param page 页码
     * @param size 页长
     * @return list 和 Long 类型 total
     */
    Map<String, Object> list(String pid, int page, int size);

    /**
     * 检查原文并写入评论；检查拒绝或不可用时不落库。
     * @param uid 已验证作者编号
     * @param body 包含 pid 和 content 的 JSON 对象
     * @return 新评论 cid
     */
    Map<String, Object> create(String uid, Map<String, Object> body);

    /**
     * 按完整复合主键删除本人或管理员有权删除的展示作品评论。
     * @param cid 评论编号
     * @param uid 已验证操作者编号
     * @param role 已验证角色
     */
    void delete(String cid, String uid, String role);
}
