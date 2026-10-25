-- 输入：db_pg 的最新备份已导入测试库；写库目标固定，禁止用于生产迁移。
-- 注意：本脚本不写死库名，调用方需先选中目标库（mysql <db> < V001__baseline.sql）
SET NAMES utf8mb4;
DROP PROCEDURE IF EXISTS c00_baseline;
DELIMITER $$
CREATE PROCEDURE c00_baseline()
BEGIN
    DECLARE finished BOOLEAN DEFAULT FALSE;
    DECLARE tbl VARCHAR(64);
    DECLARE col VARCHAR(64);
    DECLARE counter_default VARCHAR(20);
    DECLARE fk_name VARCHAR(64);
    DECLARE parent_tbl VARCHAR(64);
    DECLARE parent_col VARCHAR(64);
    DECLARE delete_rule VARCHAR(10);
    DECLARE counters CURSOR FOR
        SELECT table_name,column_name,column_default FROM information_schema.columns
        WHERE table_schema=DATABASE() AND (
            (table_name='pwork' AND column_name IN ('viewcount','votecount','likep')) OR
            (table_name='comment' AND column_name='likecom') OR
            (table_name='pcatogory' AND column_name='pc_count') OR
            (table_name='gift' AND column_name IN ('stocknum','gneed')) OR
            (table_name='user' AND column_name='vote'));
    DECLARE foreign_keys CURSOR FOR
        SELECT 'favorite','pid','fk_favorite_work','pwork','pid','CASCADE'
        UNION ALL SELECT 'comment','pid','fk_comment_work','pwork','pid','CASCADE'
        UNION ALL SELECT 'work_exif','pid','fk_exif_work','pwork','pid','CASCADE'
        UNION ALL SELECT 'work_embedding','pid','fk_embedding_work','pwork','pid','CASCADE'
        UNION ALL SELECT 'favorite','uid','fk_favorite_user','user','uid','RESTRICT'
        UNION ALL SELECT 'liketable','uid','fk_like_user','user','uid','RESTRICT'
        UNION ALL SELECT 'comment','u_id','fk_comment_user','user','uid','RESTRICT'
        UNION ALL SELECT 'follow','uid','fk_follow_user','user','uid','RESTRICT'
        UNION ALL SELECT 'follow','target_uid','fk_follow_target','user','uid','RESTRICT';
    DECLARE obsolete_foreign_keys CURSOR FOR
        SELECT r.table_name,r.constraint_name FROM information_schema.referential_constraints r
        WHERE r.constraint_schema=DATABASE() AND NOT EXISTS (
            SELECT 1 FROM information_schema.key_column_usage k
            WHERE k.constraint_schema=r.constraint_schema AND k.table_name=r.table_name
              AND k.constraint_name=r.constraint_name AND k.referenced_table_schema=DATABASE()
              AND ((k.table_name IN ('favorite','comment','work_exif','work_embedding')
                    AND k.column_name='pid' AND k.referenced_table_name='pwork'
                    AND k.referenced_column_name='pid' AND r.delete_rule='CASCADE')
                OR (((k.table_name IN ('favorite','liketable','follow') AND k.column_name='uid')
                     OR (k.table_name='comment' AND k.column_name='u_id')
                     OR (k.table_name='follow' AND k.column_name='target_uid'))
                    AND k.referenced_table_name='user' AND k.referenced_column_name='uid'
                    AND r.delete_rule='RESTRICT')));
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET finished=TRUE;

    -- 脏值检查前置到任何数据修改之前；越界数字也必须人工决定，不能截断。
    IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND
        ((table_name='pwork' AND column_name IN ('viewcount','votecount','likep')) OR
         (table_name='comment' AND column_name='likecom') OR
         (table_name='pcatogory' AND column_name='pc_count') OR
         (table_name='gift' AND column_name IN ('stocknum','gneed')) OR
         (table_name='user' AND column_name='vote')))!=8 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Expected eight counter columns';
    END IF;
    OPEN counters;
    check_counters: LOOP
        FETCH counters INTO tbl,col,counter_default;
        IF finished THEN LEAVE check_counters; END IF;
        SET @sql=CONCAT('SELECT COUNT(*) INTO @dirty FROM `',tbl,'` WHERE `',col,
            '` IS NULL OR CAST(`',col,'` AS CHAR) NOT REGEXP ''^[0-9]+$'' OR CASE WHEN CAST(`',col,
            '` AS CHAR) REGEXP ''^[0-9]+$'' THEN CAST(`',col,'` AS DECIMAL(65,0))>4294967295 ELSE FALSE END');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
        SELECT tbl AS counter_table,col AS counter_column,@dirty AS dirty_count;
        IF @dirty>0 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Dirty counter: stop for manual decision';
        END IF;
    END LOOP;
    CLOSE counters;
    IF (SELECT COUNT(*) FROM user)>10 OR EXISTS (SELECT 1 FROM user WHERE uname NOT LIKE 'demo%' AND CHAR_LENGTH(uname)>46) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Demo seed mapping requires manual decision';
    END IF;

    -- 实库时间列叫 likect；同一点赞组中，最早时间相同时用 lid 稳定决胜。
    DELETE newer FROM liketable newer JOIN liketable older
      ON newer.uid=older.uid AND newer.targetid=older.targetid AND newer.targettype=older.targettype
     AND (newer.likect>older.likect OR (newer.likect=older.likect AND newer.lid>older.lid));
    SELECT ROW_COUNT() AS duplicate_likes_deleted;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='liketable' AND index_name='uk_uid_target') THEN
        ALTER TABLE liketable ADD UNIQUE KEY uk_uid_target(uid,targetid,targettype);
    END IF;

    SET finished=FALSE;
    OPEN counters;
    convert_counters: LOOP
        FETCH counters INTO tbl,col,counter_default;
        IF finished THEN LEAVE convert_counters; END IF;
        -- 去重时旧触发器可能减少计数，所以转换前再次检查，拒绝负数或截断。
        SET @sql=CONCAT('SELECT COUNT(*) INTO @dirty FROM `',tbl,'` WHERE CAST(`',col,
            '` AS CHAR) NOT REGEXP ''^[0-9]+$'' OR `',col,'` IS NULL');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
        IF @dirty>0 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Counter became dirty after deduplication';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=tbl AND column_name=col AND column_type='int unsigned') THEN
            SET @sql=CONCAT('ALTER TABLE `',tbl,'` MODIFY COLUMN `',col,'` INT UNSIGNED NOT NULL DEFAULT ',counter_default);
            PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
        END IF;
    END LOOP;
    CLOSE counters;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user' AND column_name='openid') THEN
        ALTER TABLE user ADD COLUMN openid VARCHAR(64) NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user' AND column_name='bio') THEN
        ALTER TABLE user ADD COLUMN bio VARCHAR(255) NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='user' AND index_name='uk_user_openid') THEN
        ALTER TABLE user ADD UNIQUE KEY uk_user_openid(openid);
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user' AND column_name='pwd' AND is_nullable='NO') THEN
        ALTER TABLE user MODIFY COLUMN pwd VARCHAR(64) NULL;
    END IF;
    -- 只在首次扩展枚举时把旧作品公开；重跑不能把后续审核状态重置。
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pwork' AND column_name='wstatus' AND column_type<>"enum('待审核','展示','隐藏','已驳回')") THEN
        ALTER TABLE pwork MODIFY COLUMN wstatus ENUM('待审核','展示','隐藏','已驳回') NOT NULL DEFAULT '待审核';
        UPDATE pwork SET wstatus='展示';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='pwork' AND index_name='idx_uid_ct') THEN
        ALTER TABLE pwork ADD KEY idx_uid_ct(uid,cwtime);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='pwork' AND index_name='idx_status_ct') THEN
        ALTER TABLE pwork ADD KEY idx_status_ct(wstatus,cwtime);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='comment' AND index_name='idx_pid_ct') THEN
        ALTER TABLE comment ADD KEY idx_pid_ct(pid,comct);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='sys_log' AND index_name='idx_ct') THEN
        ALTER TABLE sys_log ADD KEY idx_ct(createtime);
    END IF;
    DROP TRIGGER IF EXISTS tri_liketable_after_insert;
    DROP TRIGGER IF EXISTS tri_liketable_after_delete;
    CREATE OR REPLACE VIEW view_work_hot AS
        SELECT * FROM view_work_detail ORDER BY (likep+votecount) DESC;

    -- 外键两侧的字符串排序规则必须与旧表一致，不能继承服务器 0900 默认值。
    CREATE TABLE IF NOT EXISTS favorite (
        fid VARCHAR(36) NOT NULL PRIMARY KEY, uid VARCHAR(36) NOT NULL, pid VARCHAR(36) NOT NULL,
        ct DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        UNIQUE KEY uk_uid_pid(uid,pid), KEY idx_pid(pid)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
    CREATE TABLE IF NOT EXISTS follow (
        fid VARCHAR(36) NOT NULL PRIMARY KEY, uid VARCHAR(36) NOT NULL, target_uid VARCHAR(36) NOT NULL,
        ct DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        UNIQUE KEY uk_uid_target(uid,target_uid), KEY idx_target(target_uid)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
    CREATE TABLE IF NOT EXISTS work_exif (
        pid VARCHAR(36) NOT NULL PRIMARY KEY, focal_mm SMALLINT NULL, aperture DECIMAL(3,1) NULL,
        shutter VARCHAR(16) NULL, iso SMALLINT NULL, lens VARCHAR(64) NULL, camera VARCHAR(64) NULL,
        stripped TINYINT(1) NOT NULL DEFAULT 1, KEY idx_query(focal_mm,aperture)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
    CREATE TABLE IF NOT EXISTS work_embedding (
        pid VARCHAR(36) NOT NULL PRIMARY KEY, model VARCHAR(32) NOT NULL DEFAULT 'clip-vit-b16',
        dim SMALLINT NOT NULL DEFAULT 512, vec BLOB NOT NULL,
        status ENUM('pending','ready','failed') NOT NULL DEFAULT 'pending', err_msg VARCHAR(255) NULL,
        updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        KEY idx_status(status)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

    -- 实库已有十一条旧外键；按测试卡 A10 归一为九条，去掉额外关系和错误的级联规则。
    -- 只保留目标关系的正确规则，第二遍不会反复删除重建；删除约束不会删除数据。
    SET finished=FALSE;
    OPEN obsolete_foreign_keys;
    remove_obsolete_foreign_keys: LOOP
        FETCH obsolete_foreign_keys INTO tbl,fk_name;
        IF finished THEN LEAVE remove_obsolete_foreign_keys; END IF;
        SET @sql=CONCAT('ALTER TABLE `',tbl,'` DROP FOREIGN KEY `',fk_name,'`');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END LOOP;
    CLOSE obsolete_foreign_keys;
    SET finished=FALSE;
    OPEN foreign_keys;
    add_foreign_keys: LOOP
        FETCH foreign_keys INTO tbl,col,fk_name,parent_tbl,parent_col,delete_rule;
        IF finished THEN LEAVE add_foreign_keys; END IF;
        -- 多态 targetid 不参与此清理和外键；只清指定九条父子关系的孤儿。
        SET @sql=CONCAT('DELETE child FROM `',tbl,'` child LEFT JOIN `',parent_tbl,
            '` parent ON child.`',col,'`=parent.`',parent_col,'` WHERE parent.`',parent_col,'` IS NULL');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
        IF NOT EXISTS (SELECT 1 FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE() AND table_name=tbl AND column_name=col AND referenced_table_name=parent_tbl AND referenced_column_name=parent_col) THEN
            SET @sql=CONCAT('ALTER TABLE `',tbl,'` ADD CONSTRAINT `',fk_name,'` FOREIGN KEY (`',col,
                '`) REFERENCES `',parent_tbl,'` (`',parent_col,'`) ON DELETE ',delete_rule);
            PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
        END IF;
    END LOOP;
    CLOSE foreign_keys;

    -- 按 uid 给全部十个种子稳定编号；不能跳过已有假号，否则可能与新号码碰撞。
    CREATE TEMPORARY TABLE c00_demo_phones AS
        SELECT uid,CONCAT('1380000000',ROW_NUMBER() OVER (ORDER BY uid)-1) AS demo_phone FROM user;
    UPDATE user u JOIN c00_demo_phones d ON u.uid=d.uid
       SET u.pnum=d.demo_phone;
    UPDATE user SET uname=CONCAT('demo',uname) WHERE uname NOT LIKE 'demo%';
    DROP TEMPORARY TABLE c00_demo_phones;
    -- 输出：只报告迁移完成，不输出手机号、密码等种子明文。
    SELECT 'V001 complete' AS migration_result;
END$$
DELIMITER ;
CALL c00_baseline();
DROP PROCEDURE c00_baseline;
