package com.pg.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pg.domain.User;
import com.pg.mapper.UserMapper;
import com.pg.mapper.FavoriteMapper;
import com.pg.mapper.LikeMapper;
import com.pg.mapper.FollowMapper;
import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
/** 用户资料及主页业务；作品读取复用统一投影，隐私写入只接收已验证身份。 */
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final UserMapper userMapper;
    private final FavoriteMapper favoriteMapper;
    private final LikeMapper likeMapper;
    private final FollowMapper followMapper;
    private final WorkQueryServiceImpl workQueryService;

    /**
     * 创建组件并固定依赖，由 Spring 完成构造器注入。
     * @param userMapper 用户数据访问组件
     * @param favoriteMapper 收藏主页查询
     * @param likeMapper 喜欢主页查询
     * @param followMapper 关注与粉丝的共享四字段查询
     * @param workQueryService 现有作品字段白名单投影
     */
    public UserServiceImpl(UserMapper userMapper, FavoriteMapper favoriteMapper, LikeMapper likeMapper,
                           WorkQueryServiceImpl workQueryService, FollowMapper followMapper) {
        this.userMapper = userMapper;
        this.favoriteMapper = favoriteMapper;
        this.likeMapper = likeMapper;
        this.followMapper = followMapper;
        this.workQueryService = workQueryService;
    }

    /** {@inheritDoc} */
    @Override
    public List<User> queryUser(Map<String, Object> param) {
        return userMapper.queryUser(param);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> home(String uid, String viewerUid, String role) {
        Map<String, Object> state = requireHome(uid, viewerUid);
        UserMapper.HomeProfile user = userMapper.homeProfile(uid);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        // 保留旧资料键和 Date 类型；只从安全列构建响应，不能把整个实体交给前端。
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("uid", user.getUid());
        data.put("uname", user.getUname());
        data.put("role", user.getRole());
        data.put("avatar", user.getAvatar());
        data.put("vote", user.getVote());
        data.put("uct", user.getUct());
        data.put("status", user.getStatus());
        data.put("bio", user.getBio());
        data.putAll(state);
        data.put("likedTotal", user.getLikedTotal());
        return data;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> works(String uid, String viewerUid, int page, int size) {
        long offset = offset(page, size);
        requireHome(uid, viewerUid);
        Long total = userMapper.worksCount(uid);
        return projectPage(userMapper.works(uid, offset, size), total);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> favorites(String uid, String viewerUid, int page, int size) {
        long offset = offset(page, size);
        Map<String, Object> state = requireHome(uid, viewerUid);
        if (!Boolean.TRUE.equals(state.get("showFavorites"))) {
            throw new BizException(ErrorCode.FORBIDDEN, "该用户的收藏不可见");
        }
        Long total = favoriteMapper.homeCount(uid, viewerUid);
        return projectPage(favoriteMapper.homePage(uid, viewerUid, offset, size), total);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> likes(String uid, String viewerUid, int page, int size) {
        long offset = offset(page, size);
        Map<String, Object> state = requireHome(uid, viewerUid);
        if (!Boolean.TRUE.equals(state.get("showLikes"))) {
            throw new BizException(ErrorCode.FORBIDDEN, "该用户的喜欢不可见");
        }
        Long total = likeMapper.homeCount(uid, viewerUid);
        return projectPage(likeMapper.homePage(uid, viewerUid, offset, size), total);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> following(String uid, String viewerUid, int page, int size) {
        return relationPage(uid, viewerUid, false, page, size);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> followers(String uid, String viewerUid, int page, int size) {
        return relationPage(uid, viewerUid, true, page, size);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public Map<String, Object> updatePrivacy(String uid, Map<String, Object> body) {
        if (uid == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        // 输入来自 JSON 对象；严格检查原始类型，避免字符串、数字被自动转换成布尔。
        if (body == null || body.containsKey("uid")
                || !(body.get("visFavorite") instanceof Boolean)
                || !(body.get("visLike") instanceof Boolean)
                || !(body.get("visFollowing") instanceof Boolean)
                || !(body.get("visFollowers") instanceof Boolean)) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "必须提交四个布尔开关，且不得指定用户");
        }
        requireHome(uid, uid);
        boolean visFavorite = (Boolean) body.get("visFavorite");
        boolean visLike = (Boolean) body.get("visLike");
        boolean visFollowing = (Boolean) body.get("visFollowing");
        boolean visFollowers = (Boolean) body.get("visFollowers");
        // WHERE 的身份只来自请求属性；重复保存相同值也应成功，不依赖驱动的更新行数口径。
        userMapper.updatePrivacy(uid, visFavorite, visLike, visFollowing, visFollowers);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("visFavorite", visFavorite);
        data.put("visLike", visLike);
        data.put("visFollowing", visFollowing);
        data.put("visFollowers", visFollowers);
        return data;
    }

    private Map<String, Object> relationPage(String uid, String viewerUid, boolean followers, int page, int size) {
        if (viewerUid == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        long offset = offset(page, size);
        Map<String, Object> state = requireHome(uid, viewerUid);
        // 目标存在后才判断隐私；本人始终可读，不能因关闭公开而被误拦截。
        String visibility = followers ? "showFollowers" : "showFollowing";
        if (!uid.equals(viewerUid) && !Boolean.TRUE.equals(state.get(visibility))) {
            throw new BizException(ErrorCode.FORBIDDEN, "该用户的关系列表不可见");
        }
        // 输入的 uid 是路径目标，viewerUid 只用于鉴权，不能误查请求者本人的关系。
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("uid", uid);
        query.put("followers", followers);
        query.put("size", size);
        query.put("offset", offset);
        Long total = followMapper.homeCount(query);
        List<Map<String, Object>> rows = followMapper.homePage(query);
        rows.replaceAll(row -> {
            // 与 FollowServiceImpl 一致：补齐 NULL 键，出口只允许共享投影的四个字段。
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("uid", row.get("uid"));
            user.put("nickname", row.get("nickname"));
            user.put("avatar", row.get("avatar"));
            user.put("bio", row.get("bio"));
            return user;
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", rows);
        data.put("total", total);
        return data;
    }

    private Map<String, Object> requireHome(String uid, String viewerUid) {
        if (uid == null || !uid.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "用户编号格式不合法");
        }
        Map<String, Object> state = userMapper.homeState(uid, viewerUid);
        if (state == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return state;
    }

    private long offset(int page, int size) {
        if (page < 1 || size < 1 || size > 50) { // B5 §4.1：每页最多 50 条，限制单次查询开销。
            throw new BizException(ErrorCode.INVALID_REQUEST, "分页参数不合法");
        }
        return ((long) page - 1) * size;
    }

    private Map<String, Object> projectPage(List<Map<String, Object>> rows, Long total) {
        // Mapper 的原始行不能直接返回；统一补齐 pcid、favoriteCount 等作品白名单键。
        rows.replaceAll(row -> workQueryService.project(row, false));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", rows);
        data.put("total", total);
        return data;
    }
}
