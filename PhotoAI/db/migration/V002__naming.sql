-- 输入：已应用 V001 的备份已导入隔离库；本次只在 db_pg_naming_test 执行，禁止写 db_pg。
-- 注意：不写死库名，调用方先选库；命名统一必须早于卡02–07的迁移和实现。
SET NAMES utf8mb4;
DROP PROCEDURE IF EXISTS naming_migrate;
DELIMITER $$
CREATE PROCEDURE naming_migrate()
BEGIN
    DECLARE finished BOOLEAN DEFAULT FALSE;
    DECLARE tbl VARCHAR(64);
    DECLARE old_col VARCHAR(64);
    DECLARE new_col VARCHAR(64);
    DECLARE renames CURSOR FOR
        SELECT 'follow','target_uid','tuid'
        UNION ALL SELECT 'work_exif','focal_mm','focal'
        UNION ALL SELECT 'work_embedding','err_msg','err'
        UNION ALL SELECT 'work_embedding','updated_at','uct'
        UNION ALL SELECT 'comment','u_id','uid';
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET finished=TRUE;

    -- 两个同义列并存或两列皆无都不能猜测合并；在任何结构变更前检查全部映射。
    OPEN renames;
    check_names: LOOP
        FETCH renames INTO tbl,old_col,new_col;
        IF finished THEN LEAVE check_names; END IF;
        IF (SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema=DATABASE() AND table_name=tbl AND column_name IN (old_col,new_col))<>1 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Expected exactly one old or new column';
        END IF;
    END LOOP;
    CLOSE renames;

    SET finished=FALSE;
    OPEN renames;
    rename_columns: LOOP
        FETCH renames INTO tbl,old_col,new_col;
        IF finished THEN LEAVE rename_columns; END IF;
        -- RENAME COLUMN 保留类型、默认值、注释，并让索引/外键随列改名；第二遍跳过。
        IF EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema=DATABASE() AND table_name=tbl AND column_name=old_col) THEN
            SET @sql=CONCAT('ALTER TABLE `',tbl,'` RENAME COLUMN `',old_col,'` TO `',new_col,'`');
            PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
        END IF;
    END LOOP;
    CLOSE renames;

    -- MySQL 不会自动改写视图里的列引用；同步修复两个依赖 comment.uid 的既有视图。
    -- 保持原来的连接和统计口径，只把评论用户列及同名输出改为 uid，避免改名后查询报错。
    IF EXISTS (SELECT 1 FROM information_schema.views WHERE table_schema=DATABASE() AND table_name='view_comment_full') THEN
        CREATE OR REPLACE VIEW view_comment_full AS
            SELECT c.cid,c.uid,c.pid,c.comcontent,c.likecom,c.comct,u.uname,u.avatar,p.wtitle
            FROM comment c LEFT JOIN user u ON c.uid=u.uid LEFT JOIN pwork p ON c.pid=p.pid;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.views WHERE table_schema=DATABASE() AND table_name='view_user_stat') THEN
        CREATE OR REPLACE VIEW view_user_stat AS
            SELECT u.uid,u.uname,u.pnum,u.role,u.pwd,u.avatar,u.vote,u.uct,u.status,
                (SELECT COUNT(*) FROM pwork WHERE pwork.uid=u.uid) AS work_count,
                (SELECT COUNT(*) FROM comment WHERE comment.uid=u.uid) AS comment_count,
                (SELECT COUNT(*) FROM vote WHERE vote.uid=u.uid) AS vote_count
            FROM user u;
    END IF;

    -- 旧 pcatogory 原样保留；独立字典与旧复合主键模型并存，不搬行、不猜历史归类。
    CREATE TABLE IF NOT EXISTS pclist (
        pcid VARCHAR(36) NOT NULL PRIMARY KEY COMMENT '分类主键ID',
        pcname VARCHAR(50) NOT NULL COMMENT '分类名称',
        cds VARCHAR(255) NULL COMMENT '分类说明',
        sort INT NOT NULL DEFAULT 0 COMMENT '分类排序，数值越小越靠前',
        ct DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
    CREATE TABLE IF NOT EXISTS waudit (
        aid VARCHAR(36) NOT NULL PRIMARY KEY COMMENT '审核日志主键ID',
        pid VARCHAR(36) NOT NULL COMMENT '作品ID',
        act VARCHAR(16) NOT NULL COMMENT '审核动作：approve或reject',
        reason VARCHAR(200) NULL COMMENT '驳回理由，通过时为空',
        uadmin VARCHAR(36) NOT NULL COMMENT '审核管理员ID',
        ct DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '审核时间',
        KEY idx_pid_ct(pid,ct,aid)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pwork' AND column_name='pcid') THEN
        ALTER TABLE pwork ADD COLUMN pcid VARCHAR(36) NULL COMMENT '分类字典ID，历史作品可为空';
    END IF;
    -- 输出：只报告结构迁移结果；不更新作品状态、账号资料或其它业务行。
    SELECT 'V002 complete' AS migration_result;
END$$
DELIMITER ;
CALL naming_migrate();
DROP PROCEDURE naming_migrate;
