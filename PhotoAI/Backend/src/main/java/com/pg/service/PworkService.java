package com.pg.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.pg.domain.Pwork;
import java.util.List;
import java.util.Map;

public interface PworkService extends IService<Pwork> {
    List<Pwork> queryPwork(Map<String, Object> param);

    /**
     * 发布作品并写入 EXIF、待编码占位；
     * @param uid 已验证作者
     * @param body 请求字段
     * @return pid/status
     */
    Map<String, Object> publish(String uid, Map<String, Object> body);

    /**
     * 作者全量编辑并重新送审；
     * @param uid 已验证作者
     * @param pid 作品编号
     * @param body 四项字段
     * @return pid/status
     */
    Map<String, Object> update(String uid, String pid, Map<String, Object> body);

    /**
     * 作者切换展示或隐藏，写入状态；相同状态幂等成功，待审核或已驳回不能切换。
     * @param uid 已验证作者
     * @param pid 作品编号
     * @param body 仅接收 status 为展示或隐藏
     * @return pid/status
     */
    Map<String, Object> updateVisibility(String uid, String pid, Map<String, Object> body);

    /**
     * 删除作品及从属数据；
     * @param uid 已验证用户
     * @param role 令牌角色
     * @param pid 作品编号
     */
    void delete(String uid, String role, String pid);
}
