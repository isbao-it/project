package com.pg.service;

import java.util.Map;

/** 封禁申诉的可信身份提交、管理员查询与处理事务契约。 */
public interface AppealService {
    /** @param body 微信临时码与申诉原文 @return 申诉编号及待处理状态；验证身份后写入申诉 */
    Map<String, Object> submit(Map<String, Object> body);

    /** @param parameters 原始状态和分页参数 @return 精确列表投影及总数，不写库 */
    Map<String, Object> list(Map<String, String> parameters);

    /** @param aid 申诉编号 @param body 处理结果及驳回理由 @param handler 已验证管理员编号
     * @return 申诉编号及新状态；接受时在同一事务恢复账号，不恢复作品 */
    Map<String, Object> handle(String aid, Map<String, Object> body, String handler);
}
