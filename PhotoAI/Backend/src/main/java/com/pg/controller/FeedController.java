package com.pg.controller;

import com.pg.entity.AjaxResult;
import com.pg.service.FeedService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 独立承接关注流固定路径，避免被作品详情动态路径吞掉。 */
@RestController
public class FeedController {
    private final FeedService feedService;

    /** @param feedService 本人关注流查询服务。 */
    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    /**
     * 分页读取本人关注作者的展示作品，不写库。
     * @param uid 已验证的本人编号
     * @param page 页码，默认一
     * @param size 页长，默认十
     * @return list/total 成功信封
     */
    @GetMapping("/pwork/following")
    public AjaxResult following(@RequestAttribute String uid,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "10") int size) {
        return AjaxResult.success(feedService.following(uid, page, size));
    }
}
