package com.pg.service;

import java.util.Map;

/** 后台审核调用面，作品状态与审核日志作为一个事务写入。 */
public interface AuditService {
    /**
     * 读取固定为待审核的作品页，无浏览计数副作用。
     * @param page 从一开始的页码
     * @param size 每页条数
     * @return list 与 Long 类型 total
     */
    Map<String, Object> pending(int page, int size);

    /**
     * 通过待审核作品并追加 pass 日志；重复审核拒绝写入。
     * @param pid 作品编号
     * @param adminUid 已验证的管理员编号
     * @return pid 与新 status
     */
    Map<String, Object> approve(String pid, String adminUid);

    /**
     * 驳回待审核作品并追加原文理由日志。
     * @param pid 作品编号
     * @param reason 待校验的 JSON 理由值
     * @param adminUid 已验证的管理员编号
     * @return pid 与新 status
     */
    Map<String, Object> reject(String pid, Object reason, String adminUid);
}
