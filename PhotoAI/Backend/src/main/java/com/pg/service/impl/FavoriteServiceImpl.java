package com.pg.service.impl;

import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.domain.Favorite;
import com.pg.mapper.FavoriteMapper;
import com.pg.service.FavoriteService;
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

/** 收藏事务；唯一键收敛重复请求，展示状态校验先于关系写入。 */
@Service
public class FavoriteServiceImpl implements FavoriteService {
    private static final Logger log = LoggerFactory.getLogger(FavoriteServiceImpl.class);
    private static final int PAGE_SIZE_MAX = 50; // 冻结§1.1：增大将改变公共分页契约。
    private static final int FAVORITE_ID_MAX = 36; // favorite.fid 列宽；用户前缀只占随机编号剩余的空间。
    private static final List<String> WORK_FIELDS = Arrays.asList("pid", "uid", "wtitle", "wdesc", "vurl",
            "viewcount", "votecount", "wstatus", "cwtime", "likep", "uname", "avatar", "pcname", "realLikeCount");
    private final FavoriteMapper favoriteMapper;

    /** @param favoriteMapper 收藏读写与作品只读 SQL。 */
    public FavoriteServiceImpl(FavoriteMapper favoriteMapper) {
        this.favoriteMapper = favoriteMapper;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> favorite(String uid, String pid) {
        requireDisplayed(pid);
        String randomId = UUID.randomUUID().toString().replace("-", "");
        String fid = uid.substring(0, Math.min(uid.length(), FAVORITE_ID_MAX - randomId.length())) + randomId;
        try {
            favoriteMapper.insert(new Favorite(fid, uid, pid));
        } catch (DuplicateKeyException duplicate) {
            // RC 读到竞争请求刚提交的收藏；只吸收本人同作品冲突，其它 SQL 错误仍交全局处理。
            if (favoriteMapper.ownedCount(uid, pid) != 1L) {
                throw duplicate;
            }
            log.info("重复收藏保持幂等 uid={} pid={}", uid, pid);
        }
        return result(pid, true);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> unfavorite(String uid, String pid) {
        requireDisplayed(pid);
        favoriteMapper.delete(uid, pid);
        return result(pid, false);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> mine(String uid, int page, int size) {
        if (page < 1 || size < 1 || size > PAGE_SIZE_MAX) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "分页参数不合法");
        }
        // 入口身份只来自已验证 uid；总数与列表使用相同可见性条件，超页保持空列表。
        Long total = favoriteMapper.mineCount(uid);
        List<Map<String, Object>> rows = favoriteMapper.mine(uid, ((long) page - 1) * size, size);
        rows.replaceAll(this::project);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        result.put("total", total);
        return result;
    }

    private void requireDisplayed(String pid) {
        if (pid == null || !pid.matches("[A-Za-z0-9_-]{1,36}")) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "作品编号不合法");
        }
        if (!favoriteMapper.displayed(pid)) {
            throw new BizException(ErrorCode.NOT_FOUND, "作品不存在");
        }
    }

    private Map<String, Object> result(String pid, boolean favorited) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("favorited", favorited);
        result.put("count", favoriteMapper.count(pid));
        return result;
    }

    private Map<String, Object> project(Map<String, Object> row) {
        // 出口保留十四键；MyBatis 默认省略 SQL NULL，必须显式补回可空字段。
        Map<String, Object> work = new LinkedHashMap<>();
        for (String field : WORK_FIELDS) {
            work.put(field, row.get(field));
        }
        return work;
    }
}
