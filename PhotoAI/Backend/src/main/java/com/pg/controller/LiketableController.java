package com.pg.controller;

import com.pg.entity.AjaxResult;
import com.pg.service.LikeService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/** 帖子点赞入口；身份取自拦截器，重复操作仍成功。 */
@RestController
@RequestMapping("/like")
public class LiketableController {
    private final LikeService likeService;

    /** @param likeService 点赞事务服务。 */
    public LiketableController(LikeService likeService) {
        this.likeService = likeService;
    }

    /**
     * 查询我点赞过的展示作品。
     * @param uid 拦截器提供的已验证用户编号
     * @param page 页码，从 1 开始
     * @param size 页长，范围 1–50
     * @return list/total 分页信封
     */
    @GetMapping("/mine")
    public AjaxResult mine(@RequestAttribute String uid,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "10") int size) {
        return AjaxResult.success(likeService.mine(uid, page, size));
    }

    /**
     * 幂等点赞并维护作品计数。
     * @param pid 作品编号
     * @param request 已验证身份
     * @return 已赞状态和帖子点赞总数信封
     */
    @PostMapping("/{pid}")
    public AjaxResult like(@PathVariable String pid, HttpServletRequest request) {
        return AjaxResult.success(likeService.like((String) request.getAttribute("uid"), pid));
    }

    /**
     * 幂等取消点赞并维护作品计数。
     * @param pid 作品编号
     * @param request 已验证身份
     * @return 未赞状态和帖子点赞总数信封
     */
    @DeleteMapping("/{pid}")
    public AjaxResult unlike(@PathVariable String pid, HttpServletRequest request) {
        return AjaxResult.success(likeService.unlike((String) request.getAttribute("uid"), pid));
    }
}
