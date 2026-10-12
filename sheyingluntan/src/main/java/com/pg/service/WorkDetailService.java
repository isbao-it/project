package com.pg.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.pg.domain.WorkDetail;

import java.util.List;
import java.util.Map;

public interface WorkDetailService extends IService<WorkDetail> {
    List<WorkDetail> selectByCondition(Map<String, Object> params);
}