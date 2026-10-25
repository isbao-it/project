package com.pg.controller;

import com.pg.entity.AjaxResult;
import com.pg.service.FavoriteService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/** 收藏入口；当前用户只取拦截器已经验证的身份。 */
@RestController
@RequestMapping("/favorite")
public class FavoriteController {
    private final FavoriteService favoriteService;

    /** @param favoriteService 收藏读写服务。 */
    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    /**
     * 幂等收藏展示作品，写入当前用户的收藏关系。
     * @param pid 作品编号
     * @param request 已验证身份
     * @return 收藏状态及该作品收藏总数信封
     */
    @PostMapping("/{pid}")
    public AjaxResult favorite(@PathVariable String pid, HttpServletRequest request) {
        return AjaxResult.success(favoriteService.favorite((String) request.getAttribute("uid"), pid));
    }

    /**
     * 幂等取消展示作品的本人收藏。
     * @param pid 作品编号
     * @param request 已验证身份
     * @return 取消状态及该作品收藏总数信封
     */
    @DeleteMapping("/{pid}")
    public AjaxResult unfavorite(@PathVariable String pid, HttpServletRequest request) {
        return AjaxResult.success(favoriteService.unfavorite((String) request.getAttribute("uid"), pid));
    }

    /**
     * 分页读取本人收藏，不改变关系或作品浏览量。
     * @param page 从一开始的页码
     * @param size 每页条数
     * @param request 已验证身份
     * @return 展示作品列表和过滤后的总数信封
     */
    @GetMapping("/mine")
    public AjaxResult mine(@RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "10") int size, HttpServletRequest request) {
        return AjaxResult.success(favoriteService.mine((String) request.getAttribute("uid"), page, size));
    }
}
