package com.pg.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pg.domain.WorkDetail;
import com.pg.mapper.WorkDetailMapper;
import com.pg.service.WorkDetailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class WorkDetailServiceImpl extends ServiceImpl<WorkDetailMapper, WorkDetail> implements WorkDetailService {

    @Autowired
    private WorkDetailMapper workDetailMapper;

    @Override
    public List<WorkDetail> selectByCondition(Map<String, Object> params) {
        return workDetailMapper.selectByCondition(params);
    }
}