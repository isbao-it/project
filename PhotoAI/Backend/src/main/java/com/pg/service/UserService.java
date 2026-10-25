package com.pg.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.pg.domain.User;
import java.util.List;
import java.util.Map;

/**
 * 用户服务接口，继承自IService接口
 * IService 内部持有一个对 BaseMapper 的引用
 * 继承主要是为了获得大量开箱即用的 CRUD（增删改查）
 * 该接口定义了用户相关的业务操作方法
 */
public interface UserService extends IService<User> {

    /**
     * 根据参数查询用户列表
     * @param param 查询参数，包含查询条件的键值对
     * 使用 Map 来接收查询参数，意味着查询条件是动态的、非固定的。
     */
    List<User> queryUser(Map<String, Object> param);

    /** @param uid 目标用户；@param viewerUid 已验证请求者，可空；@param role 请求者角色；@return 保留旧字段的脱敏主页及统计，不写库。 */
    Map<String, Object> home(String uid, String viewerUid, String role);

    /** @param uid 目标用户；@param viewerUid 已验证请求者，可空；@param page 页码；@param size 页长；@return 展示作品的 list/total，不写库。 */
    Map<String, Object> works(String uid, String viewerUid, int page, int size);

    /** @param uid 目标用户；@param viewerUid 已验证请求者，可空；@param page 页码；@param size 页长；@return 可见收藏的 list/total；关闭且非本人时拒绝，不写库。 */
    Map<String, Object> favorites(String uid, String viewerUid, int page, int size);

    /** @param uid 目标用户；@param viewerUid 已验证请求者，可空；@param page 页码；@param size 页长；@return 可见帖子赞的 list/total；关闭且非本人时拒绝，不写库。 */
    Map<String, Object> likes(String uid, String viewerUid, int page, int size);

    /** @param uid 目标用户；@param viewerUid 已验证登录用户；@param page 页码；@param size 页长；@return 可见关注的四字段 list/total；关闭且非本人时拒绝，不写库。 */
    Map<String, Object> following(String uid, String viewerUid, int page, int size);

    /** @param uid 目标用户；@param viewerUid 已验证登录用户；@param page 页码；@param size 页长；@return 可见粉丝的四字段 list/total；关闭且非本人时拒绝，不写库。 */
    Map<String, Object> followers(String uid, String viewerUid, int page, int size);

    /** @param uid 已验证本人编号；@param body 四个布尔开关，禁止携带 uid；@return 已写库的开关值。 */
    Map<String, Object> updatePrivacy(String uid, Map<String, Object> body);

}
