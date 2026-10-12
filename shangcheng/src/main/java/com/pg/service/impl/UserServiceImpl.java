package com.pg.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pg.domain.User;
import com.pg.mapper.UserMapper;
import com.pg.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Autowired
    private UserMapper userMapper;

    @Override
    public List<User> queryUser(Map<String, Object> param) {
        return userMapper.queryUser(param);
    }
}