package com.pg.controller;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.pg.domain.User;
import com.pg.service.UserService;
import com.pg.entity.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/user")
public class userController {

    @Autowired
    private UserService userService;

    /**
     * 分页查询用户（支持按用户名模糊查询）
     */
    @GetMapping("/list")
    public AjaxResult userList(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            User user) {

        // 开启分页
        PageHelper.startPage(pageNum, pageSize);

        // 构建查询条件
        Map<String, Object> param = new HashMap<>();
        param.put("uname", user.getUname());

        // 调用自定义查询方法
        List<User> listuser = userService.queryUser(param);

        // 分页包装
        PageInfo<User> page = new PageInfo<>(listuser);

        // 返回结果（total 和 list）
        return AjaxResult.success(page.getTotal(), page.getList());
    }
}