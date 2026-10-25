-- 输入：两次 mysql 退出码和迁移前作品数由执行器通过会话变量传入；缺证据即失败。
-- 注意：不写死库名，调用方先选库（table_schema=DATABASE()）
SET NAMES utf8mb4;
DROP PROCEDURE IF EXISTS c00_verify;
DELIMITER $$
CREATE PROCEDURE c00_verify()
BEGIN
    CREATE TEMPORARY TABLE c00_results (assertion VARCHAR(3), result VARCHAR(4));
    INSERT INTO c00_results
    SELECT 'A1', IF(COALESCE(@c00_exit1 = 0 AND @c00_exit2 = 0, FALSE), 'PASS', 'FAIL')
    UNION ALL SELECT 'A2', IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pwork' AND column_name IN ('viewcount','votecount','likep') AND column_type='int unsigned')=3,'PASS','FAIL')
    UNION ALL SELECT 'A3', IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_type='int unsigned' AND ((table_name='comment' AND column_name='likecom') OR (table_name='pcatogory' AND column_name='pc_count') OR (table_name='gift' AND column_name IN ('stocknum','gneed')) OR (table_name='user' AND column_name='vote')))=5,'PASS','FAIL')
    UNION ALL SELECT 'A4', IF((SELECT COUNT(*) FROM (SELECT index_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='liketable' AND index_name='uk_uid_target' AND non_unique=0 GROUP BY index_name HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index)='uid,targetid,targettype') i)=1,'PASS','FAIL')
    UNION ALL SELECT 'A5', IF((SELECT COUNT(*) FROM (SELECT uid,targetid,targettype FROM liketable GROUP BY uid,targetid,targettype HAVING COUNT(*)>1) d)=0,'PASS','FAIL')
    UNION ALL SELECT 'A6', IF((SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE())=15 AND (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name IN ('tri_liketable_after_insert','tri_liketable_after_delete'))=0 AND (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name IN ('tri_vote_after_insert','tri_vote_after_delete'))=2,'PASS','FAIL')
    UNION ALL SELECT 'A7', IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user' AND ((column_name='openid' AND column_type='varchar(64)' AND is_nullable='YES') OR (column_name='bio' AND column_type='varchar(255)' AND is_nullable='YES') OR (column_name='pwd' AND is_nullable='YES')))=3 AND (SELECT COUNT(*) FROM (SELECT index_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='user' AND non_unique=0 GROUP BY index_name HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index)='openid') i)=1,'PASS','FAIL')
    UNION ALL SELECT 'A8', IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pwork' AND column_name='wstatus' AND column_type="enum('待审核','展示','隐藏','已驳回')" AND column_default='待审核')=1 AND (SELECT COUNT(*) FROM pwork WHERE wstatus='展示')=COALESCE(@c00_pwork_before,-1),'PASS','FAIL')
    UNION ALL SELECT 'A9', IF((SELECT COUNT(DISTINCT CONCAT(table_name,':',cols)) FROM (SELECT table_name,GROUP_CONCAT(column_name ORDER BY seq_in_index) cols FROM information_schema.statistics WHERE table_schema=DATABASE() GROUP BY table_name,index_name) i WHERE (table_name='pwork' AND cols IN ('uid,cwtime','wstatus,cwtime')) OR (table_name='comment' AND cols='pid,comct') OR (table_name='sys_log' AND cols='createtime'))=4,'PASS','FAIL')
    UNION ALL SELECT 'A10', IF((SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE())=9 AND (SELECT COUNT(*) FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.table_name=k.table_name AND r.constraint_name=k.constraint_name WHERE k.constraint_schema=DATABASE() AND k.referenced_table_schema=DATABASE() AND ((k.table_name IN ('favorite','comment','work_exif','work_embedding') AND k.column_name='pid' AND k.referenced_table_name='pwork' AND k.referenced_column_name='pid' AND r.delete_rule='CASCADE') OR (((k.table_name IN ('favorite','liketable','follow') AND k.column_name='uid') OR (k.table_name='comment' AND k.column_name='u_id') OR (k.table_name='follow' AND k.column_name='target_uid')) AND k.referenced_table_name='user' AND k.referenced_column_name='uid' AND r.delete_rule='RESTRICT')))=9 AND (SELECT COUNT(*) FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE() AND table_name='liketable' AND column_name='targetid' AND referenced_table_name IS NOT NULL)=0,'PASS','FAIL')
    UNION ALL SELECT 'A11', IF((SELECT COUNT(*) FROM information_schema.views WHERE table_schema=DATABASE() AND table_name='view_work_hot' AND LOWER(view_definition) NOT LIKE '%cast(%' AND LOWER(view_definition) LIKE '%order by%')=1,'PASS','FAIL')
    UNION ALL SELECT 'A12', IF((SELECT COUNT(*) FROM user WHERE pnum NOT REGEXP '^1380000000[0-9]$' OR uname NOT LIKE 'demo%')=0 AND (SELECT COUNT(DISTINCT pnum) FROM user)=(SELECT COUNT(*) FROM user),'PASS','FAIL');
    -- 输出：每条断言均可审计；有失败则让 mysql 返回非零，不能仅打印 FAIL。
    SELECT assertion,result FROM c00_results;
    IF EXISTS (SELECT 1 FROM c00_results WHERE result='FAIL') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C00 verification failed';
    END IF;
    DROP TEMPORARY TABLE c00_results;
END$$
DELIMITER ;
CALL c00_verify();
DROP PROCEDURE c00_verify;
