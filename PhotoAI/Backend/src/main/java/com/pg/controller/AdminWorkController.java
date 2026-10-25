package com.pg.controller;

import com.pg.common.RequiresRole;
import com.pg.entity.AjaxResult;
import com.pg.service.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/** 管理员作品审核入口，只消费拦截器已验证的操作者身份。 */
@RestController
@RequestMapping("/admin/work")
@RequiresRole("管理员")
public class AdminWorkController {
    private final AuditService auditService;

    /** @param auditService 独立审核事务服务 */
    public AdminWorkController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * 只读待审核分页，额外状态参数不参与查询。
     * @param page 页码，默认一
     * @param size 条数，默认十
     * @return 待审核作品分页信封
     */
    @GetMapping("/pending")
    public AjaxResult pending(@RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "10") int size) {
        return AjaxResult.success(auditService.pending(page, size));
    }

    /**
     * 将待审核作品改为展示并写入审核日志。
     * @param pid 路径作品编号
     * @param request 包含已验证 uid 的请求
     * @return pid 与展示状态信封
     */
    @PutMapping("/{pid}/approve")
    public AjaxResult approve(@PathVariable String pid, HttpServletRequest request) {
        return AjaxResult.success(auditService.approve(pid, (String) request.getAttribute("uid")));
    }

    /**
     * 将待审核作品驳回并记录理由原文。
     * @param pid 路径作品编号
     * @param body JSON 理由对象，不接受客户端自报身份
     * @param request 包含已验证 uid 的请求
     * @return pid 与已驳回状态信封
     */
    @PutMapping("/{pid}/reject")
    public AjaxResult reject(@PathVariable String pid, @RequestBody Map<String, Object> body,
                             HttpServletRequest request) {
        return AjaxResult.success(auditService.reject(pid, body.get("reason"),
                (String) request.getAttribute("uid")));
    }
}
