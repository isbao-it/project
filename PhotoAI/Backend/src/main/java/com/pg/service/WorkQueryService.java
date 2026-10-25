package com.pg.service;

import java.util.Map;

/** 提供冻结作品投影，不将旧实体及其计数类型暴露给新接口。 */
public interface WorkQueryService {
    /**
     * 查询展示作品或作者本人的详情，并原子增加浏览次数；
     * @param pid 作品编号
     * @param viewerUid 已验证的访问者编号，匿名时为 null
     * @return work/exif/author 三项投影
     */
    Map<String, Object> detail(String pid, String viewerUid);

    /**
     * 分页查询展示作品；
     * @param tab 排序页签
     * @param category 可空分类
     * @param page 页码
     * @param size 页长
     * @param viewerUid 已验证的访问者编号，匿名时为 null，仅用于推荐排序
     * @return list/total
     */
    Map<String, Object> list(String tab, String category, int page, int size, String viewerUid);

    /**
     * 查询本人作品及最近审核理由；
     * @param uid 本人编号
     * @param status 可空状态
     * @param page 页码
     * @param size 页长
     * @return list/total
     */
    Map<String, Object> mine(String uid, String status, int page, int size);
}
