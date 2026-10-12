package com.pg.mapper;

import com.pg.domain.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/**
 * @Entity com.pg.domain.User
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
    List<User> queryUser(Map<String,Object> param); //分页查询用户接口
}




