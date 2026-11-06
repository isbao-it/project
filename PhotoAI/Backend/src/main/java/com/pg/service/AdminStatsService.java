package com.pg.service;

import java.util.Map;

/** 管理概览只读调用面，不缓存统计结果。 */
public interface AdminStatsService {
    /** @return 同一数据库快照中的七字段概览 */
    Map<String, Object> stats();
}
