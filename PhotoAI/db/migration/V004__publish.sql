-- 卡03：发布先占位 pending，实际向量由卡08填写；只放宽 vec，不改变其它列。
-- BLOB 的既有默认值为 NULL（原 NOT NULL 禁止显式写入），重复执行结果相同。
ALTER TABLE work_embedding MODIFY COLUMN vec BLOB NULL;
