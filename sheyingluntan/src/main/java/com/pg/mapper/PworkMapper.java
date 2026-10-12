package com.pg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pg.domain.Pwork;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;
import java.util.Map;

@Mapper
public interface PworkMapper extends BaseMapper<Pwork> {
    List<Pwork> queryPwork(Map<String, Object> param);
}