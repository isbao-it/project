package com.pg.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    // 访问这个地址，会跳转到 templates/company.html
    @GetMapping("/company/list/page")
    public String goCompanyPage() {
        return "company"; // 对应 templates/company.html
    }

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

    @GetMapping("/page/user/index")
    public String userIndex() {
        return "user/index";
    }
}
