package com.pg.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pg.domain.Pwork;
import com.pg.mapper.PworkMapper;
import com.pg.service.PworkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@Service
public class PworkServiceImpl extends ServiceImpl<PworkMapper, Pwork> implements PworkService {

    @Autowired
    private PworkMapper pworkMapper;

    @Override
    public List<Pwork> queryPwork(Map<String, Object> param) {
        return pworkMapper.queryPwork(param);
    }
}