-- 卡 B5/B8：用户主页可见性开关（收藏/喜欢/关注/粉丝）。
-- 卡 B5：手机号改为唯一 —— 登录按 pnum 走 selectOne，重复号码会让登录直接 500。
-- 幂等：先查 information_schema 再改；pnum 有重复时主动报错中止，不静默跳过。
-- 执行器：mysql 客户端（需要 DELIMITER），不要用 JDBC。

DROP PROCEDURE IF EXISTS pg_v005_visibility_and_pnum;

DELIMITER $$

CREATE PROCEDURE pg_v005_visibility_and_pnum()
BEGIN
    -- 1) 四个可见性开关，默认全部可见
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'vis_favorite') THEN
        ALTER TABLE user ADD COLUMN vis_favorite TINYINT(1) NOT NULL DEFAULT 1 COMMENT '收藏是否对他人可见';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'vis_like') THEN
        ALTER TABLE user ADD COLUMN vis_like TINYINT(1) NOT NULL DEFAULT 1 COMMENT '喜欢是否对他人可见';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'vis_following') THEN
        ALTER TABLE user ADD COLUMN vis_following TINYINT(1) NOT NULL DEFAULT 1 COMMENT '关注列表是否对他人可见';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'vis_followers') THEN
        ALTER TABLE user ADD COLUMN vis_followers TINYINT(1) NOT NULL DEFAULT 1 COMMENT '粉丝列表是否对他人可见';
    END IF;

    -- 2) pnum 唯一：有重复号码时必须先清理，否则登录 selectOne 会命中多行
    IF EXISTS (SELECT 1 FROM user WHERE pnum IS NOT NULL AND pnum <> ''
               GROUP BY pnum HAVING COUNT(*) > 1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'user.pnum 存在重复号码，请先清理再执行本迁移';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user'
                     AND INDEX_NAME = 'uk_user_pnum' AND NON_UNIQUE = 0) THEN
        -- 基线里的 AK_uni_pnum 是非唯一索引，名字带 uni 但不唯一，直接改成唯一会撞名
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND INDEX_NAME = 'AK_uni_pnum') THEN
            ALTER TABLE user DROP INDEX AK_uni_pnum;
        END IF;

        ALTER TABLE user ADD UNIQUE INDEX uk_user_pnum (pnum);
    END IF;
END$$

DELIMITER ;

CALL pg_v005_visibility_and_pnum();

DROP PROCEDURE pg_v005_visibility_and_pnum;

-- 验收查询（人工核对，不改变数据）：
--   SHOW COLUMNS FROM user LIKE 'vis_%';                       -- 期望 4 行
--   SHOW INDEX FROM user WHERE Key_name = 'uk_user_pnum';       -- 期望 Non_unique = 0
