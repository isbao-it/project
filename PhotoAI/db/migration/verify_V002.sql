-- 输入：执行器传入两遍退出码 @naming_exit1/@naming_exit2，以及迁移前全部基表行数 JSON @naming_before。
-- 注意：只在 db_pg_naming_test 验证；缺少基线或任一断言失败必须返回非零。
SET NAMES utf8mb4;
DROP PROCEDURE IF EXISTS naming_verify;
DELIMITER $$
CREATE PROCEDURE naming_verify()
BEGIN
    DECLARE finished BOOLEAN DEFAULT FALSE;
    DECLARE tbl VARCHAR(64);
    DECLARE rowcounts_ok BOOLEAN DEFAULT TRUE;
    DECLARE tables_before CURSOR FOR
        SELECT table_name FROM information_schema.tables
        WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND table_name NOT IN ('pclist','waudit');
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET finished=TRUE;

    -- 安全闸：任何库都能验证，但禁止直接对着生产库 db_pg 验证（要验证就导一份副本）
    IF DATABASE() IS NULL OR DATABASE()='db_pg' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Refuse to verify on production db_pg; use a copy';
    END IF;
    CREATE TEMPORARY TABLE naming_results (assertion VARCHAR(64), result VARCHAR(4));
    INSERT INTO naming_results
    SELECT 'N01_two_exits',IF(COALESCE(@naming_exit1=0 AND @naming_exit2=0,FALSE),'PASS','FAIL')
    UNION ALL SELECT 'N02_follow_tuid',IF(
        (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='follow' AND column_name='tuid')=1 AND
        (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='follow' AND column_name='target_uid')=0,'PASS','FAIL')
    UNION ALL SELECT 'N03_exif_focal',IF(
        (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='work_exif' AND column_name='focal')=1 AND
        (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='work_exif' AND column_name='focal_mm')=0,'PASS','FAIL')
    UNION ALL SELECT 'N04_embedding_err',IF(
        (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='work_embedding' AND column_name='err')=1 AND
        (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='work_embedding' AND column_name='err_msg')=0,'PASS','FAIL')
    UNION ALL SELECT 'N05_embedding_uct',IF(
        (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='work_embedding' AND column_name='uct')=1 AND
        (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='work_embedding' AND column_name='updated_at')=0,'PASS','FAIL')
    UNION ALL SELECT 'N06_comment_uid',IF(
        (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='comment' AND column_name='uid')=1 AND
        (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='comment' AND column_name='u_id')=0,'PASS','FAIL')
    UNION ALL SELECT 'N07_pclist',IF(
        (SELECT GROUP_CONCAT(column_name ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pclist')='pcid,pcname,cds,sort,ct' AND
        (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pclist' AND column_name='pcid' AND column_key='PRI' AND column_type='varchar(36)')=1,'PASS','FAIL')
    UNION ALL SELECT 'N08_waudit',IF(
        (SELECT GROUP_CONCAT(column_name ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='waudit')='aid,pid,act,reason,uadmin,ct' AND
        (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='waudit' AND column_name='aid' AND column_key='PRI' AND column_type='varchar(36)')=1,'PASS','FAIL')
    UNION ALL SELECT 'N09_pwork_pcid',IF(
        (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pwork' AND column_name='pcid' AND column_type='varchar(36)' AND is_nullable='YES')=1,'PASS','FAIL')
    UNION ALL SELECT 'N11_renamed_constraints',IF(
        (SELECT COUNT(*) FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE() AND referenced_table_name='user' AND referenced_column_name='uid' AND ((table_name='follow' AND column_name='tuid') OR (table_name='comment' AND column_name='uid')))=2 AND
        (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='follow' AND index_name='uk_uid_target' AND non_unique=0)='uid,tuid' AND
        (SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='comment' AND index_name='PRIMARY')='pid,cid,uid','PASS','FAIL')
    UNION ALL SELECT 'N12_renamed_types_defaults',IF(
        (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND
            ((table_name='follow' AND column_name='tuid' AND column_type='varchar(36)' AND is_nullable='NO') OR
             (table_name='comment' AND column_name='uid' AND column_type='varchar(36)' AND is_nullable='NO') OR
             (table_name='work_exif' AND column_name='focal' AND column_type='smallint' AND is_nullable='YES') OR
             (table_name='work_embedding' AND column_name='err' AND column_type='varchar(255)' AND is_nullable='YES') OR
             (table_name='work_embedding' AND column_name='uct' AND column_type='datetime' AND is_nullable='NO' AND LOWER(column_default)='current_timestamp' AND LOWER(extra) LIKE '%on update current_timestamp%')))=5,'PASS','FAIL')
    UNION ALL SELECT 'N13_views_use_uid',IF(
        (SELECT COUNT(*) FROM information_schema.views WHERE table_schema=DATABASE() AND table_name IN ('view_comment_full','view_user_stat') AND view_definition NOT LIKE '%`u_id`%')=2,'PASS','FAIL');

    -- information_schema 的 TABLE_ROWS 是估算，必须逐表 COUNT，不能只比较作品数或总行数。
    IF @naming_before IS NULL OR NOT JSON_VALID(@naming_before) THEN
        SET rowcounts_ok=FALSE;
    ELSE
        IF JSON_LENGTH(@naming_before)<>(SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND table_name NOT IN ('pclist','waudit')) THEN
            SET rowcounts_ok=FALSE;
        END IF;
        OPEN tables_before;
        check_rows: LOOP
            FETCH tables_before INTO tbl;
            IF finished THEN LEAVE check_rows; END IF;
            SET @sql=CONCAT('SELECT COUNT(*) INTO @actual_rows FROM `',tbl,'`');
            PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
            IF NOT COALESCE(@actual_rows=CAST(JSON_UNQUOTE(JSON_EXTRACT(@naming_before,CONCAT('$."',tbl,'"'))) AS UNSIGNED),FALSE) THEN
                SET rowcounts_ok=FALSE;
            END IF;
        END LOOP;
        CLOSE tables_before;
    END IF;
    INSERT INTO naming_results VALUES ('N10_all_original_rowcounts',IF(rowcounts_ok,'PASS','FAIL'));
    -- 输出：先显示全部断言，再以 SQLSTATE 让失败传到 mysql 退出码。
    SELECT assertion,result FROM naming_results ORDER BY assertion;
    IF EXISTS (SELECT 1 FROM naming_results WHERE result='FAIL') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V002 verification failed';
    END IF;
    DROP TEMPORARY TABLE naming_results;
END$$
DELIMITER ;
CALL naming_verify();
DROP PROCEDURE naming_verify;
