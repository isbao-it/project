package com.pg.service.impl;

import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.domain.Follow;
import com.pg.mapper.FollowMapper;
import com.pg.service.FollowService;
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

/** 关注关系事务与只读分页；真实唯一键收敛并发，不维护易失计数。 */
@Service
public class FollowServiceImpl implements FollowService {
    private static final Logger log = LoggerFactory.getLogger(FollowServiceImpl.class);
    private static final int PAGE_SIZE_MAX = 50; // 冻结§1.1 的页长上限，不能擅自放宽。
    private static final List<String> USER_FIELDS = Arrays.asList("uid", "nickname", "avatar", "bio");
    private final FollowMapper followMapper;

    /** @param followMapper 关注关系与用户投影 SQL。 */
    public FollowServiceImpl(FollowMapper followMapper) {
        this.followMapper = followMapper;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> follow(String uid, String tuid) {
        requireTarget(tuid);
        if (uid.equals(tuid)) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "不能关注自己");
        }
        // 入口只有已验证 uid 和路径 tuid；客户端不能指定 fid、创建时间或操作者。
        Follow follow = new Follow(UUID.randomUUID().toString(), uid, tuid);
        try {
            followMapper.insert(follow);
        } catch (DuplicateKeyException duplicate) {
            // RC 可读到等待唯一键期间提交的关系；只吸收同一关系重复，不能吞其它键故障。
            if (followMapper.ownedCount(uid, tuid) != 1L) {
                throw duplicate;
            }
            log.info("重复关注保持幂等 uid={} tuid={}", uid, tuid);
        }
        return result(tuid, true);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> unfollow(String uid, String tuid) {
        requireTarget(tuid);
        followMapper.delete(uid, tuid);
        return result(tuid, false);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> removeFollower(String ownerUid, String followerUid) {
        if (followerUid == null || !followerUid.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "用户编号不合法");
        }
        if (ownerUid.equals(followerUid)) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "不能移除自己");
        }
        // 入口 ownerUid 来自已验证身份；删除对方到本人的关系，以受影响行数避免并发重复删除误报成功。
        if (followMapper.removeFollower(followerUid, ownerUid) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "该用户不是本人的粉丝");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("removed", true);
        // 出口计数在同一事务内删除后查询，不能沿用取关响应中目标用户的粉丝数。
        result.put("followersCount", followMapper.followersCount(ownerUid));
        return result;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> following(String uid, int page, int size) {
        return readPage(uid, false, page, size);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> followers(String uid, int page, int size) {
        return readPage(uid, true, page, size);
    }

    private void requireTarget(String tuid) {
        if (tuid == null || !tuid.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "用户编号不合法");
        }
        if (!followMapper.userExists(tuid)) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
    }

    private Map<String, Object> result(String tuid, boolean following) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("following", following);
        result.put("followers", followMapper.followersCount(tuid));
        return result;
    }

    private Map<String, Object> readPage(String uid, boolean followers, int page, int size) {
        if (page < 1 || size < 1 || size > PAGE_SIZE_MAX) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "关注列表分页参数不合法");
        }
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("uid", uid);
        query.put("followers", followers);
        query.put("size", size);
        query.put("offset", ((long) page - 1) * size);
        Long total = followMapper.count(query);
        List<Map<String, Object>> rows = followMapper.page(query);
        rows.replaceAll(row -> {
            // 出口显式补齐 nullable 键，MyBatis 默认忽略 NULL 列也不能让响应丢字段。
            Map<String, Object> user = new LinkedHashMap<>();
            for (String field : USER_FIELDS) {
                user.put(field, row.get(field));
            }
            return user;
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", rows);
        result.put("total", total);
        return result;
    }
}
