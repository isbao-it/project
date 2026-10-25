package com.pg.controller;

import com.pg.entity.AjaxResult;
import com.pg.service.CategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 分类只读入口；字典与作品计数均由服务查询，不写入历史分类。 */
@RestController
@RequestMapping("/category")
public class CategoryController {
    private final CategoryService categoryService;

    /** @param categoryService 分类字典查询服务。 */
    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** @return 按字典顺序排列的分类和展示作品数信封。 */
    @GetMapping("/list")
    public AjaxResult list() {
        return AjaxResult.success(categoryService.list());
    }
}
