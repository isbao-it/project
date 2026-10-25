-- 输入：已应用 V001/V002 的隔离测试库；只放宽账号资料三列，保留数据、索引、默认值和列注释。
-- ALTER COLUMN 的 DROP NOT NULL 不受 MySQL 支持，MODIFY 必须完整保留原列定义。
SET NAMES utf8mb4;
ALTER TABLE `user`
    MODIFY COLUMN `uname` VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '用户名',
    MODIFY COLUMN `pnum` VARCHAR(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '手机号',
    MODIFY COLUMN `avatar` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '头像地址';
-- 输出：仅三列可空性变化；重复执行不改变任何业务行或约束。
