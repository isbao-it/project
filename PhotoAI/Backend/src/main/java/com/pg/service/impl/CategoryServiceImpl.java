package com.pg.service.impl;

import com.pg.mapper.CategoryMapper;
import com.pg.service.CategoryService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** 分类查询；直接聚合展示作品，避免收藏或点赞连接导致计数扇出。 */
@Service
public class CategoryServiceImpl implements CategoryService {
    private final CategoryMapper categoryMapper;

    /** @param categoryMapper 新分类字典与展示作品聚合 SQL。 */
    public CategoryServiceImpl(CategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> list() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", categoryMapper.list());
        return result;
    }
}
