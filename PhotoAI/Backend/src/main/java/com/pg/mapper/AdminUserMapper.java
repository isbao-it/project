package com.pg.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/** 用户管理独立投影及写入，保护检查使用同一事务中的当前锁定读。 */
@Mapper
public interface AdminUserMapper {
    /** @param keyword 昵称片段或 null @return 匹配用户总数 */
    Long count(@Param("keyword") String keyword);

    /**
     * 查询八字段用户投影。
     * @param keyword 昵称片段或 null
     * @param size 条数
     * @param offset 长整型偏移
     * @return 不包含密码和微信身份的用户行
     */
    List<Map<String, Object>> page(@Param("keyword") String keyword, @Param("size") int size,
                                   @Param("offset") long offset);

    /** @return 按 uid 稳定顺序锁定后重读的正常管理员编号集合 */
    List<String> lockNormalAdmins();

    /** @param uid 目标用户编号 @return 锁定后的 uid/role/status 或 null */
    Map<String, Object> findForUpdate(@Param("uid") String uid);

    /** @param uid 目标用户编号 @return 任意状态作品总数 */
    Long countWorks(@Param("uid") String uid);

    /** @param uid 目标编号 @param status 已校验状态 @return 更新行数，仅写 status */
    int updateStatus(@Param("uid") String uid, @Param("status") String status);

    /** @param uid 目标用户编号 @return 删除行数，不清理其它关联 */
    int delete(@Param("uid") String uid);
}
