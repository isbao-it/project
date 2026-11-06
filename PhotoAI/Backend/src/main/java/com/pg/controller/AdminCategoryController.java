package com.pg.controller;

import com.pg.common.BizException;
import com.pg.common.RequiresRole;
import com.pg.entity.AjaxResult;
import com.pg.service.CategoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 管理员分类新增入口，保留公开分类查询的既有行为。 */
@RestController
@RequiresRole("管理员")
public class AdminCategoryController {
    private final CategoryService categoryService;

    /** @param categoryService 分类查询与新增事务服务 */
    public AdminCategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** @param body 原始分类名称和说明，客户端编号不参与写入 @return 精确三键的新增分类信封 */
    @PostMapping("/admin/category")
    public AjaxResult create(@RequestBody Map<String, Object> body) {
        return AjaxResult.success(categoryService.create(body));
    }

    /** @param exception 仅由本功能产生的固定业务说明 @return 保留分类已存在等固定文案的失败信封 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<AjaxResult> rejected(BizException exception) {
        // 全局出口仅使用枚举通用文案；局部出口保留本契约的固定提示，不影响旧接口。
        return ResponseEntity.status(exception.getHttpStatus())
                .body(AjaxResult.error(exception.getCode()).put("msg", exception.getMessage()));
    }
}
