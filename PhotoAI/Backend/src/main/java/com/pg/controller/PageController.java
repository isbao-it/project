package com.pg.controller;

import com.pg.common.RequiresRole;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

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

    @GetMapping("/pwork/list/page")
    public String pworkList() {
        return "pwork/pworkList";
    }

    @GetMapping("/view/workdetail/list/page")
    public String workDetailList() {
        return "view/workDetailList";
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

    @GetMapping("/page/user/index")
    public String userIndex() {
        return "user/index";
    }
}
