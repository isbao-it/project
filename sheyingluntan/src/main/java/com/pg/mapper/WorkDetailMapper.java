package com.pg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pg.domain.WorkDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

@Mapper
public interface WorkDetailMapper extends BaseMapper<WorkDetail> {
    List<WorkDetail> selectByCondition(Map<String, Object> params);
}