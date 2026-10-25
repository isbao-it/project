# C00 / V001 测试库迁移

仅允许写入 `db_pg_mig_test`。`db_pg` 只用于查询与备份；两个 SQL 文件开头均固定 `USE db_pg_mig_test`。
执行目录为项目根目录，MySQL 客户端为 8.0.23。密码从 `MYSQL_PWD` 环境变量提供，不写入文件。
需要 `mysql.exe` 执行 `DELIMITER`，不能改用 JDBC。

## R02 已执行的步骤

1. 从实库读取六张表计数，保存 `counts_before.json`，不使用旧 `photoai.sql` 的行数。
2. 使用 `mysqldump --single-transaction --skip-lock-tables --skip-dump-date --routines --triggers --hex-blob db_pg` 导出 `backup_before_V001.sql`。备份包含原始种子数据，仅用于恢复。
3. 确认备份不含生产库的显式库名引用，重建测试库并导入。
4. 先运行验收 SQL，记录迁移前的预期失败；随后运行 V001 两遍，每遍检查退出码和六表计数。
5. 将两次退出码、迁移前作品数作为会话变量输入验收 SQL，运行 A1–A12；执行四项负向检查。
6. 再次只读导出生产库，比较完整 dump 指纹与六表行数，确认与备份前一致。

实际执行器和日志在 `_Outputs/待审核/C00/R02/details/`。执行器只使用 Python 标准库启动原生 MySQL 客户端，没有新增依赖。

## 手工执行迁移与验收

先完成备份与测试库导入，才可运行以下命令。PowerShell 的 `>` 可能生成 UTF-16；导出请使用 mysqldump 的 `--result-file`，导入请用 mysql 的 `source`。

```powershell
$MBIN = 'C:/Program Files/MySQL/MySQL Server 8.0/bin'
# MYSQL_PWD 由操作人预先设置；不要把密码提交到文件。
& "$MBIN/mysqldump.exe" -uroot --default-character-set=utf8mb4 --single-transaction --skip-lock-tables --skip-dump-date --routines --triggers --hex-blob --result-file=db/migration/backup_before_V001.sql db_pg
if ($LASTEXITCODE -ne 0) { throw '备份失败' }
& "$MBIN/mysql.exe" -uroot -e 'DROP DATABASE IF EXISTS db_pg_mig_test; CREATE DATABASE db_pg_mig_test CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;'
if ($LASTEXITCODE -ne 0) { throw '建库失败' }
& "$MBIN/mysql.exe" -uroot --default-character-set=utf8mb4 db_pg_mig_test -e 'source db/migration/backup_before_V001.sql'
if ($LASTEXITCODE -ne 0) { throw '导入失败' }
# 先记录六表 COUNT(*)，以下示例中的作品数只用于本次 R02。
& "$MBIN/mysql.exe" -uroot --default-character-set=utf8mb4 db_pg_mig_test -e 'source db/migration/V001__baseline.sql'
$ex1 = $LASTEXITCODE
if ($ex1 -ne 0) { throw '第一遍失败，停止' }
& "$MBIN/mysql.exe" -uroot --default-character-set=utf8mb4 db_pg_mig_test -e 'source db/migration/V001__baseline.sql'
$ex2 = $LASTEXITCODE
if ($ex2 -ne 0) { throw '第二遍失败，停止' }
& "$MBIN/mysql.exe" -uroot --default-character-set=utf8mb4 db_pg_mig_test -e "SET @c00_exit1=$ex1, @c00_exit2=$ex2, @c00_pwork_before=9;`nsource db/migration/verify_V001.sql"
if ($LASTEXITCODE -ne 0) { throw '验收失败' }
```

验收脚本不伪造 A1：直接运行且未传入两次真实退出码时，A1 为 FAIL。A8 使用本次迁移前作品数；换数据快照时须传入新的实测值。

## 迁移决策与失败处理

- 架构文档旧摘要的 `need` 以卡片及实库 `gneed` 为准；点赞时间字段为实库 `likect`。
- 架构旧文中“删四个计数触发器”以卡片要求为准：只删 `tri_liketable_after_insert/delete`，保留 vote 及其他触发器。
- 实库已有 11 条外键。为满足 A10 的精确 9 条要求，删除目标之外的旧约束，将 `comment.u_id`、`liketable.uid` 的旧 CASCADE 换为 RESTRICT；正确目标关系保留。仅改测试库，不删除任何父表数据。
- 新表使用 `utf8mb4_general_ci`，与现有外键父列一致。
- 非数字、NULL、超出 INT UNSIGNED 范围的计数会报错停止；不转换为 0。DDL 会隐式提交，失败后不能依赖 ROLLBACK 撤销整个迁移。
- 作品状态只在首次扩展枚举时更新为展示；重复运行不会重置后续审核状态。演示名前缀不重复叠加；全部种子手机号按 UID 稳定编号，避免跳过已有假号后产生碰撞。A12 同时检查号码格式、唯一性和演示名前缀。
- 行数异常应停止并保留 SQL 原始输出，不补数据凑数。

## 一行回退（PowerShell，只恢复测试库）

```powershell
& 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe' -uroot --default-character-set=utf8mb4 -e 'DROP DATABASE IF EXISTS db_pg_mig_test; CREATE DATABASE db_pg_mig_test CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;'; if ($LASTEXITCODE -ne 0) { throw '重建失败' }; & 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe' -uroot --default-character-set=utf8mb4 db_pg_mig_test -e 'source db/migration/backup_before_V001.sql'; if ($LASTEXITCODE -ne 0) { throw '恢复失败' }
```

该回退会丢弃测试库迁移后的所有改动并恢复备份，不对 `db_pg` 执行任何写操作。本轮验证了同一备份的测试库重建和导入；迁移后执行上述整行回退命令未验证，因为需保留最终验收状态。
