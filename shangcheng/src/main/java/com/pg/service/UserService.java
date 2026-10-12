package com.pg.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.pg.domain.User;
import java.util.List;
import java.util.Map;

public interface UserService extends IService<User> {

    // 👇 添加这个方法
    List<User> queryUser(Map<String, Object> param);

}