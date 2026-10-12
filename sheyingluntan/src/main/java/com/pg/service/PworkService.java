package com.pg.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.pg.domain.Pwork;
import java.util.List;
import java.util.Map;

public interface PworkService extends IService<Pwork> {
    List<Pwork> queryPwork(Map<String, Object> param);
}