package com.pg.service;

import java.util.Map;

/** 管理员用户管理调用面，不复用旧用户管理入口。 */
public interface AdminUserService {
    /**
     * 按昵称查询管理员可读的用户页。
     * @param keyword 可空的昵称片段
     * @param page 从一开始的页码
     * @param size 每页条数
     * @return 八字段用户 list 与 Long 类型 total
     */
    Map<String, Object> list(String keyword, int page, int size);

    /**
     * 修改目标用户状态；事务内保护自己与最后一个正常管理员。
     * @param uid 目标用户编号
     * @param status 待校验的 JSON 状态值
     * @param adminUid 已验证操作者编号
     * @return uid 与实际 status
     */
    Map<String, Object> changeStatus(String uid, Object status, String adminUid);

    /**
     * 删除无作品的目标用户；事务内检查保护规则，不清理关联业务数据。
     * @param uid 目标用户编号
     * @param adminUid 已验证操作者编号
     */
    void delete(String uid, String adminUid);
}
