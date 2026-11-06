package com.pg.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 申诉表与账号状态访问；所有请求参数由 MyBatis 绑定。 */
@Mapper
public interface AppealMapper {
    /** @param openid 微信可信身份 @return 已锁定的账号编号和状态，不存在返回空 */
    Map<String, Object> lockUser(@Param("openid") String openid);

    /** @param uid 已锁定账号编号 @return 二十四小时内待处理数量 */
    long pendingCount(@Param("uid") String uid);

    /** @param uid 已锁定账号编号 @return 最近一分钟内提交数量，包含已处理申诉 */
    long minuteCount(@Param("uid") String uid);

    /** @param appeal 服务端生成的编号、账号及原文 @return 新写入申诉行数 */
    int insert(Map<String, Object> appeal);

    /** @param status 可空状态 @return 同条件申诉总数 */
    Long count(@Param("status") String status);

    /** @param query 状态、页大小和偏移量 @return 按提交时间和编号倒序排列的申诉投影 */
    List<Map<String, Object>> page(Map<String, Object> query);

    /** @param aid 申诉编号 @return 锁定的申诉编号、账号和状态，不存在返回空 */
    Map<String, Object> lockAppeal(@Param("aid") String aid);

    /** @param appeal 编号、目标状态、操作者及理由 @return 待处理状态条件更新的行数 */
    int handle(Map<String, Object> appeal);

    /** @param uid 申诉账号编号 @return 恢复正常的账号行数，不更新作品表 */
    int enableUser(@Param("uid") String uid);
}
