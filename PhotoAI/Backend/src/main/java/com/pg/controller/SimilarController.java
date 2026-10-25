package com.pg.controller;

import com.pg.entity.AjaxResult;
import com.pg.service.SimilarService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 同款只读入口；不经详情接口，避免读取时增加浏览量。 */
@RestController
@RequestMapping("/pwork/similar")
public class SimilarController {
    private final SimilarService similarService;

    /** @param similarService 同分类与拍摄参数匹配服务。 */
    public SimilarController(SimilarService similarService) {
        this.similarService = similarService;
    }

    /**
     * 查找符合双参数阈值的展示作品。
     * @param pid 源作品编号
     * @param limit 返回条数，缺省六条
     * @return 仅包含作品列表的成功信封
     */
    @GetMapping("/{pid}")
    public AjaxResult similar(@PathVariable String pid, @RequestParam(defaultValue = "6") int limit) {
        return AjaxResult.success(similarService.similar(pid, limit));
    }
}
