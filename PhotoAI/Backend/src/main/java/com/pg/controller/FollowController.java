package com.pg.controller;

import com.pg.entity.AjaxResult;
import com.pg.service.FollowService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 关注及本人关系列表入口；操作者只取拦截器验签后的身份。 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    private final FollowService followService;

    /** @param followService 关注关系事务与查询服务。 */
    public FollowController(FollowService followService) {
        this.followService = followService;
    }

    /**
     * 幂等关注目标用户并写库。
     * @param uid 路径中的被关注者
     * @param currentUid 已验证的操作者
     * @return 关注状态及目标粉丝数信封
     */
    @PostMapping("/{uid}")
    public AjaxResult follow(@PathVariable String uid, @RequestAttribute("uid") String currentUid) {
        return AjaxResult.success(followService.follow(currentUid, uid));
    }

    /**
     * 幂等取消本人对目标的关注并写库。
     * @param uid 路径中的被关注者
     * @param currentUid 已验证的操作者
     * @return 未关注状态及目标粉丝数信封
     */
    @DeleteMapping("/{uid}")
    public AjaxResult unfollow(@PathVariable String uid, @RequestAttribute("uid") String currentUid) {
        return AjaxResult.success(followService.unfollow(currentUid, uid));
    }

    /**
     * 移除关注本人的指定粉丝并写库。
     * @param uid 路径中的粉丝编号
     * @param currentUid 已验证的本人编号
     * @return 移除状态及本人的最新粉丝数信封
     */
    @DeleteMapping("/follower/{uid}")
    public AjaxResult removeFollower(@PathVariable String uid, @RequestAttribute("uid") String currentUid) {
        return AjaxResult.success(followService.removeFollower(currentUid, uid));
    }

    /**
     * 读取本人的关注列表。
     * @param uid 已验证的本人编号
     * @param page 页码，默认一
     * @param size 页长，默认十
     * @return list/total 成功信封
     */
    @GetMapping("/following")
    public AjaxResult following(@RequestAttribute String uid,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "10") int size) {
        return AjaxResult.success(followService.following(uid, page, size));
    }

    /**
     * 读取本人的粉丝列表。
     * @param uid 已验证的本人编号
     * @param page 页码，默认一
     * @param size 页长，默认十
     * @return list/total 成功信封
     */
    @GetMapping("/followers")
    public AjaxResult followers(@RequestAttribute String uid,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "10") int size) {
        return AjaxResult.success(followService.followers(uid, page, size));
    }
}
