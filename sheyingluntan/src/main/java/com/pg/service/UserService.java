package com.pg.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.pg.domain.User;
import java.util.List;
import java.util.Map;

/**
 * 用户服务接口，继承自IService接口
 * IService 内部持有一个对 BaseMapper 的引用
 * 继承主要是为了获得大量开箱即用的 CRUD（增删改查）
 * 该接口定义了用户相关的业务操作方法
 */
public interface UserService extends IService<User> {

    /**
     * 根据参数查询用户列表
     * @param param 查询参数，包含查询条件的键值对
     * 使用 Map 来接收查询参数，意味着查询条件是动态的、非固定的。
     */
    List<User> queryUser(Map<String, Object> param);

}