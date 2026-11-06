package com.pg.controller;

import com.pg.common.RequiresRole;
import com.pg.entity.AjaxResult;
import com.pg.service.AdminStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理端概览入口，只返回同一快照内的聚合数据。 */
@RestController
@RequiresRole("管理员")
public class AdminStatsController {
    private final AdminStatsService adminStatsService;

    /** @param adminStatsService 只读概览服务 */
    public AdminStatsController(AdminStatsService adminStatsService) {
        this.adminStatsService = adminStatsService;
    }

    /** @return 七字段管理概览的成功信封 */
    @GetMapping("/admin/stats")
    public AjaxResult stats() {
        return AjaxResult.success(adminStatsService.stats());
    }
}
