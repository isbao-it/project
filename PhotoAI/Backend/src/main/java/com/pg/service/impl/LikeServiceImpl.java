package com.pg.service.impl;

import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.domain.Like;
import com.pg.mapper.LikeMapper;
import com.pg.service.LikeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 帖子赞事务；唯一键收敛并发重复，失败时记录与计数一起回滚。 */
@Service
public class LikeServiceImpl implements LikeService {
    private static final Logger log = LoggerFactory.getLogger(LikeServiceImpl.class);
    private static final int PAGE_SIZE_MAX = 50; // 冻结§1.1：改动上限会改变公共分页契约。
    private static final List<String> WORK_FIELDS = Arrays.asList("pid", "uid", "wtitle", "wdesc", "vurl",
            "viewcount", "votecount", "wstatus", "cwtime", "likep", "uname", "avatar", "pcname", "realLikeCount");
    private final LikeMapper likeMapper;

    /** @param likeMapper 帖子赞及原子计数 SQL。 */
    public LikeServiceImpl(LikeMapper likeMapper) {
        this.likeMapper = likeMapper;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> mine(String uid, int page, int size) {
        if (page < 1 || size < 1 || size > PAGE_SIZE_MAX) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "分页参数不合法");
        }
        // 入口只接受已验证 uid；先转 long，避免大页码在计算偏移时溢出。
        long offset = ((long) page - 1) * size;
        Long total = likeMapper.countByUser(uid);
        List<Map<String, Object>> rows = likeMapper.pageByUser(uid, size, offset);
        // MyBatis 默认省略 SQL NULL；出口补齐作品列表的可空键，保持 /pwork/list 结构。
        rows.replaceAll(row -> {
            Map<String, Object> work = new LinkedHashMap<>();
            for (String field : WORK_FIELDS) {
                work.put(field, row.get(field));
            }
            return work;
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        result.put("total", total);
        return result;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> like(String uid, String pid) {
        requireDisplayed(pid);
        Like like = new Like(UUID.randomUUID().toString(), uid, pid);
        int inserted;
        try {
            inserted = likeMapper.insert(like);
        } catch (DuplicateKeyException duplicate) {
            // RC 让等待唯一键的请求读到刚提交的赞，避免 RR 旧快照误报 count=0。
            // 只吸收本人同作品帖子赞的冲突，其它键冲突仍交给全局 500 出口。
            if (likeMapper.ownedCount(uid, pid) != 1L) {
                throw duplicate;
            }
            inserted = 0;
            log.info("重复点赞保持幂等 uid={} pid={}", uid, pid);
        }
        if (inserted > 0 && likeMapper.increment(pid) == 0) {
            throw notFound();
        }
        return result(pid, true);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> unlike(String uid, String pid) {
        requireDisplayed(pid);
        // DELETE 的实际行数决定是否减计数，并发重复取消不会重复减去别人的赞。
        if (likeMapper.delete(uid, pid) > 0 && likeMapper.decrement(pid) == 0) {
            throw notFound();
        }
        return result(pid, false);
    }

    private void requireDisplayed(String pid) {
        if (pid == null || !pid.matches("[A-Za-z0-9_-]{1,36}")) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "作品编号不合法");
        }
        if (!likeMapper.displayed(pid)) {
            throw notFound();
        }
    }

    private Map<String, Object> result(String pid, boolean liked) {
        // 出口按真实帖子赞记录 COUNT，排除同 targetid 的评论赞和其它作品。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("liked", liked);
        result.put("count", likeMapper.count(pid));
        return result;
    }

    private BizException notFound() {
        return new BizException(ErrorCode.NOT_FOUND, "作品不存在");
    }
}
