package com.pg.mapper;

import com.pg.domain.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 评论专用 SQL；不调用会增加浏览量的作品详情服务。 */
@Mapper
public interface CommentMapper {
    /** @param pid 作品编号；@return 是否存在展示作品。 */
    boolean displayed(@Param("pid") String pid);

    /** @param pid 作品编号；@return 评论总行数。 */
    Long count(@Param("pid") String pid);

    /**
     * 读取稳定排序的一页评论及作者公开资料。
     * @param pid 作品编号
     * @param offset 分页偏移
     * @param size 页长
     * @return 评论持久层投影
     */
    List<Comment> page(@Param("pid") String pid, @Param("offset") long offset, @Param("size") int size);

    /** @param comment 已校验且含服务端身份的评论；@return 插入行数，作品非展示时为零。 */
    int insert(Comment comment);

    /** @param cid 评论编号；@return 含完整复合主键的评论或 null；历史重复编号交由查询异常拒绝，避免误删。 */
    Comment findByCid(@Param("cid") String cid);

    /** @param comment 含完整复合主键的目标；@return 实际删除行数，非展示作品不删除。 */
    int delete(Comment comment);
}
