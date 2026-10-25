package com.pg.service.impl;

import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.domain.Comment;
import com.pg.mapper.CommentMapper;
import com.pg.service.CommentService;
import com.pg.service.MsgSecCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** 评论校验、安全检查与公开投影；不记录正文或敏感身份信息。 */
@Service
public class CommentServiceImpl implements CommentService {
    private static final Logger log = LoggerFactory.getLogger(CommentServiceImpl.class);
    private static final int CONTENT_MAX = 500; // 冻结§1.5：按 Unicode 字符计数，不截断原文。
    private static final int PAGE_SIZE_MAX = 50; // 冻结§1.1：统一分页上限。
    private final CommentMapper commentMapper;
    private final MsgSecCheck msgSecCheck;

    /**
     * 固定评论持久层和唯一文本安全依赖。
     * @param commentMapper 评论 SQL
     * @param msgSecCheck 复用卡03文本安全接口
     */
    public CommentServiceImpl(CommentMapper commentMapper, MsgSecCheck msgSecCheck) {
        this.commentMapper = commentMapper;
        this.msgSecCheck = msgSecCheck;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> list(String pid, int page, int size) {
        validateId(pid);
        if (page < 1 || size < 1 || size > PAGE_SIZE_MAX) {
            throw invalid();
        }
        requireDisplayed(pid);
        Long total = commentMapper.count(pid);
        List<Map<String, Object>> rows = commentMapper.page(pid, ((long) page - 1) * size, size)
                .stream().map(this::project).collect(Collectors.toList());
        // 出口只交付冻结的评论字段和总数，不序列化持久层对象。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        result.put("total", total);
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> create(String uid, Map<String, Object> body) {
        // 入口是 JSON 对象，只读取 pid/content，客户端身份和计数一律不进入写入对象。
        if (body == null || !(body.get("pid") instanceof String) || !(body.get("content") instanceof String)) {
            throw invalid();
        }
        String pid = (String) body.get("pid");
        String content = (String) body.get("content");
        validateId(pid);
        if (content.codePointCount(0, content.length()) > CONTENT_MAX
                || content.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp))) {
            throw invalid();
        }
        requireDisplayed(pid);
        msgSecCheck.check(uid, content);
        Comment comment = new Comment();
        comment.setCid(UUID.randomUUID().toString());
        comment.setPid(pid);
        comment.setUid(uid);
        comment.setContent(content);
        // 安全检查可能耗时，SQL 再检查展示状态，避免检查期间隐藏的作品收到新评论。
        if (commentMapper.insert(comment) == 0) {
            throw notFound();
        }
        log.info("评论发布成功 uid={} pid={} cid={}", uid, pid, comment.getCid());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cid", comment.getCid());
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public void delete(String cid, String uid, String role) {
        validateId(cid);
        Comment comment = commentMapper.findByCid(cid);
        if (comment == null) {
            throw notFound();
        }
        requireDisplayed(comment.getPid());
        if (!uid.equals(comment.getUid()) && !"管理员".equals(role)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权删除该评论");
        }
        // cid 并非单列唯一键，删除必须带上查得的 pid/uid，不能扩成批量删除。
        if (commentMapper.delete(comment) == 0) {
            throw notFound();
        }
        log.info("评论删除成功 uid={} cid={}", uid, cid);
    }

    private Map<String, Object> project(Comment comment) {
        Map<String, Object> author = new LinkedHashMap<>();
        author.put("uid", comment.getUid());
        author.put("nickname", comment.getNickname());
        author.put("avatar", comment.getAvatar());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cid", comment.getCid());
        result.put("pid", comment.getPid());
        result.put("uid", comment.getUid());
        result.put("content", comment.getContent());
        result.put("createdAt", comment.getCreatedAt());
        result.put("author", author);
        return result;
    }

    private void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,36}")) {
            throw invalid();
        }
    }

    private void requireDisplayed(String pid) {
        if (!commentMapper.displayed(pid)) {
            throw notFound();
        }
    }

    private BizException invalid() {
        return new BizException(ErrorCode.INVALID_REQUEST, "评论参数不合法");
    }

    private BizException notFound() {
        return new BizException(ErrorCode.NOT_FOUND, "评论或作品不存在");
    }
}
