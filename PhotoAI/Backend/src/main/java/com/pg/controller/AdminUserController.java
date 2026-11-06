package com.pg.controller;

import com.pg.common.RequiresRole;
import com.pg.entity.AjaxResult;
import com.pg.service.AdminUserService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/** 管理员用户接口，完整手机号仅在管理员授权后投影。 */
@RestController
@RequestMapping("/admin/user")
@RequiresRole("管理员")
public class AdminUserController {
    private final AdminUserService adminUserService;

    /** @param adminUserService 用户管理事务服务 */
    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    /** @param uids 逗号分隔的用户编号 @param request 已验证操作者 @return 本页按钮保护信封 */
    @GetMapping("/guards")
    public AjaxResult guards(@RequestParam String uids, HttpServletRequest request) {
        return AjaxResult.success(adminUserService.guards(uids, (String) request.getAttribute("uid")));
    }

    /**
     * 查询用户分页，无数据库写入。
     * @param keyword 可选昵称片段
     * @param page 页码，默认一
     * @param size 条数，默认十
     * @return 管理员用户分页信封
     */
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) String keyword,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "10") int size) {
        return AjaxResult.success(adminUserService.list(keyword, page, size));
    }

    /**
     * 启用或禁用用户，服务事务负责保护管理员数量。
     * @param uid 目标用户编号
     * @param body JSON 状态对象
     * @param request 含已验证操作者 uid 的请求
     * @return uid 与状态信封
     */
    @PutMapping("/{uid}/status")
    public AjaxResult status(@PathVariable String uid, @RequestBody Map<String, Object> body,
                             HttpServletRequest request) {
        return AjaxResult.success(adminUserService.changeStatus(uid, body.get("status"),
                (String) request.getAttribute("uid")));
    }

    /**
     * 删除用户；仅字面量 cascade=true 时一并清理作品及关联数据。
     * @param uid 目标用户编号
     * @param cascade 可选原始参数，不转换类型、不去除空白
     * @param request 含已验证操作者 uid 的请求
     * @return 普通删除 data 为 null；级联删除返回 uid 与实际删除作品数
     */
    @DeleteMapping("/{uid}")
    public AjaxResult delete(@PathVariable String uid, @RequestParam(required = false) String cascade,
                             HttpServletRequest request) {
        return AjaxResult.success(adminUserService.delete(uid, (String) request.getAttribute("uid"),
                "true".equals(cascade)));
    }
}
