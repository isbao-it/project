package com.pg.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pg.domain.WorkDetail;
import com.pg.mapper.WorkDetailMapper;
import com.pg.service.WorkDetailService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class WorkDetailServiceImpl extends ServiceImpl<WorkDetailMapper, WorkDetail> implements WorkDetailService {

    private final WorkDetailMapper workDetailMapper;

    /**
     * 创建组件并固定依赖，由 Spring 完成构造器注入。
     * @param workDetailMapper 作品详情数据访问组件
     */
    public WorkDetailServiceImpl(WorkDetailMapper workDetailMapper) {
        this.workDetailMapper = workDetailMapper;
    }

    @Override
    public List<WorkDetail> selectByCondition(Map<String, Object> params) {
        return workDetailMapper.selectByCondition(params);
    }
}