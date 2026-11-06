package com.pg.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 新字典分类 SQL；不访问旧作品分类关系。 */
@Mapper
public interface CategoryMapper {
    /** @return sort、pcid 升序的全部分类及 Long 类型展示作品数。 */
    List<Map<String, Object>> list();

    /** @return 已锁定的分类编号；串行化新增时的同名判定与排序分配 */
    List<String> lockCategories();

    /** @param pcname 不裁剪的名称 @return 字节完全相等的名称数量，避免库排序规则忽略大小写或尾空格 */
    long countName(@Param("pcname") String pcname);

    /** @param category 服务端编号、名称及说明 @return 写入行数，排序在插入 SQL 内计算 */
    int insert(Map<String, Object> category);

    /** @param pcid 新生成的分类编号 @return 新增行的真实排序 */
    Long sortOf(@Param("pcid") String pcid);
}
