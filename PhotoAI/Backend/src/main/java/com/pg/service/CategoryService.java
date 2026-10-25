package com.pg.service;

import java.util.Map;

/** 新分类字典的只读查询契约。 */
public interface CategoryService {
    /** @return 仅含 list 的分类投影，每项为编号、名称和展示作品数。 */
    Map<String, Object> list();
}
