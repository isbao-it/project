package com.pg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pg.domain.Pwork;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

@Mapper
public interface PworkMapper extends BaseMapper<Pwork> {
    List<Pwork> queryPwork(Map<String, Object> param);

    /**
     * 锁定作品，供改删检查；
     * @param pid 作品编号
     * @return 作者字段或 null
     */
    Map<String, Object> lockOwner(@Param("pid") String pid);
    /**
     * 核验分类存在；
     * @param pcid 分类编号
     * @return 行数
     */
    Long categoryCount(@Param("pcid") String pcid);
    /**
     * 保存服务端构造的作品；
     * @param work 已校验字段
     * @return 行数
     */
    int insertPublished(Map<String, Object> work);
    /**
     * 为卡08保存待处理占位；
     * @param pid 新作品编号
     * @return 行数
     */
    int insertPendingEmbedding(@Param("pid") String pid);
    /**
     * 全量编辑四项并回待审核；
     * @param work 已校验字段
     * @return 行数
     */
    int updatePublished(Map<String, Object> work);
    /**
     * 仅作者本人且当前展示或隐藏时更新可见性；
     * @param pid 作品编号
     * @param uid 已验证作者
     * @param status 目标展示或隐藏状态
     * @return 更新行数
     */
    int updateVisibility(@Param("pid") String pid, @Param("uid") String uid, @Param("status") String status);
    /**
     * 只读查询作者与状态，供条件更新未命中时区分错误及幂等结果；
     * @param pid 作品编号
     * @return uid/wstatus 或 null
     */
    Map<String, Object> findOwnerStatus(@Param("pid") String pid);
    /**
     * 清理无外键的帖子点赞；
     * @param pid 作品编号
     * @return 删除行数
     */
    int deletePostLikes(@Param("pid") String pid);
    /**
     * 删除作品，由真实外键级联从属数据；
     * @param pid 作品编号
     * @return 删除行数
     */
    int deletePublished(@Param("pid") String pid);
}
