package com.pg.mapper;

import com.pg.domain.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * @Entity com.pg.domain.User
 */
//@Mapper 注解被 MyBatis 框架识别为 Mapper 接口
@Mapper
//接口继承了 BaseMapper<User>，这是 MyBatis-Plus 提供的基础映射接口。
//通过泛型指定实体类 User，BaseMapper
//会自动将 User 类与数据库中的对应表建立映射关系，
//并默认提供一系列常用的 CRUD（增删改查）方法
public interface UserMapper extends BaseMapper<User> {
//    BaseMapper 的基础方法根据传入的动态参数映射查询用户列表。
//    使用 Map 传参适用于查询条件不固定、动态拼接的场景
    List<User> queryUser(Map<String,Object> param);

}




