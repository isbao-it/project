package com.pg.controller;

import com.pg.common.RequiresRole;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import javax.servlet.http.HttpServletRequest;

@Controller
public class PageController {
    @GetMapping("/user/list/page")
    public String userList() {
        return "user/userList";
    }

    @GetMapping("/user/add/page")
    public String addUserPage() {
        return "user/adduser";   // 对应 templates/adduser.html
    }

    @GetMapping("/user/edit/page")
    public String editUserPage() {
        return "user/editUser";  // 对应 templates/editUser.html
    }

    /** @param request 已验证的请求身份 @return 管理员跳转新作品页，其余身份保留旧作品模板 */
    @GetMapping("/pwork/list/page")
    public String pworkList(HttpServletRequest request) {
        if ("管理员".equals(request.getAttribute("role"))) {
            return "redirect:/page/admin/works";
        }
        return "pwork/pworkList";
    }

    /** @param request 已验证的请求身份 @return 管理员跳转新作品页，其余身份保留旧详情列表模板 */
    @GetMapping("/view/workdetail/list/page")
    public String workDetailList(HttpServletRequest request) {
        if ("管理员".equals(request.getAttribute("role"))) {
            return "redirect:/page/admin/works";
        }
        return "view/workDetailList";
    }

    /** @return 管理员作品页模板；未授权请求由拦截器跳转登录页 */
    @RequiresRole("管理员")
    @GetMapping("/page/admin/works")
    public String worksPage() {
        return "pwork/pworkList";
    }

    /** @return 管理员用户页模板；未授权请求由拦截器跳转登录页 */
    @RequiresRole("管理员")
    @GetMapping("/page/admin/users")
    public String usersPage() {
        return "user/userList";
    }

    @GetMapping("/login/page")
    public String loginPage() {
        return "login";
    }

    @GetMapping("/page/admin/index")
    public String adminIndex() {
        return "admin/index";
    }

    /** @return 管理员审核页模板；授权失败沿用拦截器的登录页跳转 */
    @RequiresRole("管理员")
    @GetMapping("/page/admin/audit")
    public String auditPage() {
        return "admin/audit";
    }

    /** @return 申诉处理页模板；被封禁用户的申诉在此接受或驳回 */
    @RequiresRole("管理员")
    @GetMapping("/page/admin/appeals")
    public String appealPage() {
        return "admin/appeal";
    }

    @GetMapping("/page/user/index")
    public String userIndex() {
        return "user/index";
    }
}
