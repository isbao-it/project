package com.pg.service;

import java.util.Map;

/** 分类字典的查询与管理员新增契约。 */
public interface CategoryService {
    /** @return 仅含 list 的分类投影，每项为编号、名称和展示作品数。 */
    Map<String, Object> list();

    /** @param body 原始名称与可选说明 @return 新增分类的编号、名称和排序；写入分类字典 */
    Map<String, Object> create(Map<String, Object> body);
}
