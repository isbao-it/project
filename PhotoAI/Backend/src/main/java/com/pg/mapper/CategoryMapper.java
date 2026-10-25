package com.pg.mapper;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/** 新字典分类 SQL；不访问旧作品分类关系。 */
@Mapper
public interface CategoryMapper {
    /** @return sort、pcid 升序的全部分类及 Long 类型展示作品数。 */
    List<Map<String, Object>> list();
}
