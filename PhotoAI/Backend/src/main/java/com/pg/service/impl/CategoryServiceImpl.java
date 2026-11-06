package com.pg.service.impl;

import com.pg.mapper.CategoryMapper;
import com.pg.common.BizException;
import com.pg.common.ErrorCode;
import com.pg.service.CategoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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

    /** {@inheritDoc} */
    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        // 入口保留 JSON 类型和原文；不能把数字转成名称，也不能用 trim 改写分类身份。
        Object name = body == null ? null : body.get("pcname");
        Object description = body == null ? null : body.get("cds");
        if (!(name instanceof String) || !validText((String) name, 20)
                || (description != null && (!(description instanceof String)
                || ((String) description).codePointCount(0, ((String) description).length()) > 255))) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "分类参数不合法");
        }
        // 字典没有名称唯一索引，先以固定顺序锁定现有行，防止并发检查通过后重复新增。
        categoryMapper.lockCategories();
        if (categoryMapper.countName((String) name) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "分类已存在");
        }
        String pcid = UUID.randomUUID().toString();
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("pcid", pcid);
        category.put("pcname", name);
        category.put("cds", description);
        if (categoryMapper.insert(category) != 1) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "分类写入失败");
        }
        // 出口只投影契约三键，排序回读数据库真实分配值。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pcid", pcid);
        result.put("pcname", name);
        result.put("sort", categoryMapper.sortOf(pcid));
        return result;
    }

    private boolean validText(String text, int limit) {
        return text.codePointCount(0, text.length()) <= limit
                && text.codePoints().anyMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c));
    }
}
