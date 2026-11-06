package com.pg.service;

import java.util.Map;

/** 管理员作品读取与非待审状态修改调用面。 */
public interface AdminWorkService {
    /** @param parameters 原始查询参数，不接受旧分页别名 @return 十七键作品列表及总数 */
    Map<String, Object> list(Map<String, String> parameters);

    /** @param pid 作品编号 @return 不增加浏览数的 work、exif、author 三项详情 */
    Map<String, Object> detail(String pid);

    /** @param pid 作品编号 @param status 待校验的 JSON 值 @param adminUid 已验证管理员 @return 实际状态；必要时同事务追加通过日志 */
    Map<String, Object> changeStatus(String pid, Object status, String adminUid);
}
