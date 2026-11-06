---
卡号: C00
类型: 执行卡（数据库迁移脚本，不改 Java）
项目: PhotoAI Hub
模块: DB baseline
状态: 已终审（2026-10-18 用户「派卡吧」）
派工: Codex CLI / `-m gpt-6.1-sol` / `-c model_reasoning_effort="high"`
配套测试卡: docs/cards/C00_DB迁移_测试卡.md
---

# C00 数据库基线迁移（V001）

## 0. 终审三块速读（已给用户看过）

**验收 GWT**
- **G**：新建空库 `db_pg_mig_test`，导入 `db_pg` 的最新 dump
- **W**：把 `db/migration/V001__baseline.sql` **连跑两遍**
- **T**：两遍都不报错（幂等）；`verify_V001.sql` 全部断言通过；`db_pg` 一行未变

**用户手测（≤3 分钟）**：`"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -uroot -proot db_pg_mig_test -e "SHOW CREATE TABLE pwork\G" | Select-String likep` → 必须是 `int unsigned`；`SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema='db_pg_mig_test'` → **15**（原 17，删掉 liketable 的 2 个）

**禁止行为**：改生产库 `db_pg`、改 `src/**`、删 `photoai.sql`、git 操作、删 vote 触发器

## 1. 目标

产出一份**幂等**迁移脚本：把库从"旧结构"升到架构方案 §5 的目标结构，并在**测试库**上验证两遍。

## 2. 输入（只读）

- `docs/架构方案.md` §5（表结构、索引、外键、种子数据的唯一标准）
- `photoai.sql`（**仅作参考**，真实结构以 `db_pg` 现库为准）
- 本卡 + 测试卡

## 3. 可写白名单（超出即越界）

```
db/migration/V001__baseline.sql     （新建）
db/migration/verify_V001.sql        （新建）
db/migration/README.md              （新建，写执行与回退步骤）
```

## 4. 允许的数据库操作（**只准动测试库**）

| 库 | 允许 |
|---|---|
| `db_pg` | **只读**：`mysqldump` 导出备份、`SELECT` 核对。**禁止任何 DDL/DML** |
| `db_pg_mig_test` | 自由：建库、导入、执行 V001 两遍、跑 verify |

连接：`mysql -uroot -proot`（本机 MySQL80，端口 3306）。

## 5. 迁移内容（按顺序，每步幂等）

1. **备份**：`mysqldump -uroot -proot --routines --triggers db_pg > db/migration/backup_before_V001.sql`（文件进白名单）
2. **清重复点赞**：`liketable` 按 `(uid,targetid,targettype)` 保留最早一条（`ct` 最小，`lid` 兜底），其余删除
3. **加唯一索引**：`uk_uid_target (uid,targetid,targettype)`（存在则跳过）
4. **计数改 INT UNSIGNED**：`pwork.viewcount / votecount / likep`、`comment.likecom`、`pcatogory.pc_count`、`gift.stocknum / gneed`、`user.vote`；转换前先查 `NOT REGEXP '^[0-9]+$'` 的脏值，**有脏值就停下来报告**，不许静默置 0
5. **加列**：`user.openid VARCHAR(64) NULL UNIQUE`、`user.bio VARCHAR(255) NULL`；`user.pwd` 改可空
6. **枚举扩建**：`pwork.wstatus` → `('待审核','展示','隐藏','已驳回')`，默认 `'待审核'`（存量 9 行显式更新为 `'展示'`）
7. **索引**：`pwork(uid,cwtime)`、`pwork(wstatus,cwtime)`、`comment(pid,comct)`、`sys_log(createtime)`
8. **删触发器（只删 2 个）**：`tri_liketable_after_insert`、`tri_liketable_after_delete`
   - **`tri_vote_after_*` 保留**（投票接口还在用触发器，等后续卡搬进 Java）
9. **视图去 cast**：重建 `view_work_hot`，去掉 `cast(... as unsigned)`（计数已是 INT）
10. **外键**（先清孤儿行，再有条件加）：
    - `favorite.pid / comment.pid / work_exif.pid / work_embedding.pid` → `pwork.pid` ON DELETE CASCADE
    - `favorite.uid / liketable.uid / comment.u_id / follow.uid / follow.target_uid` → `user.uid` RESTRICT
    - **`liketable.targetid` 不加外键**（多态：帖子/评论）
    - 新表 `favorite/follow/work_exif/work_embedding` 若不存在，先在 V001 里 `CREATE TABLE IF NOT EXISTS`
11. **种子数据脱敏**：`user.pnum` 中 10 个真实手机号 → `1380000000X`（X 顺序 0–9），`uname` 加 `demo` 前缀；不删除行

> 幂等实现建议：用 `information_schema` 判断后 `PREPARE/EXECUTE`，或把整段放在 `DELIMITER $$ ... $$` 的存储过程里先 `DROP PROCEDURE IF EXISTS`。

## 6. 执行与验证

> **环境前置（必读）**：`mysql`/`mysqldump` **不在 PATH**，必须用全路径：
> `$MBIN = "C:\Program Files\MySQL\MySQL Server 8.0\bin"`（已实测可连：`-uroot -proot`，MySQL 8.0.23，库 `db_pg` 存在）

```powershell
$MBIN = "C:\Program Files\MySQL\MySQL Server 8.0\bin"

# 1) 备份 + 建测试库
& "$MBIN\mysqldump.exe" -uroot -proot --routines --triggers db_pg > db/migration/backup_before_V001.sql
& "$MBIN\mysql.exe" -uroot -proot -e "DROP DATABASE IF EXISTS db_pg_mig_test; CREATE DATABASE db_pg_mig_test CHARACTER SET utf8mb4;"
& "$MBIN\mysql.exe" -uroot -proot db_pg_mig_test < db/migration/backup_before_V001.sql

# 2) 连跑两遍（第二遍必须 0 错误）
& "$MBIN\mysql.exe" -uroot -proot db_pg_mig_test < db/migration/V001__baseline.sql; Write-Host "EXIT1=$LASTEXITCODE"
& "$MBIN\mysql.exe" -uroot -proot db_pg_mig_test < db/migration/V001__baseline.sql; Write-Host "EXIT2=$LASTEXITCODE"

# 3) 断言
& "$MBIN\mysql.exe" -uroot -proot db_pg_mig_test < db/migration/verify_V001.sql

# 4) 确认生产库没被动过（对比表数/行数）
& "$MBIN\mysql.exe" -uroot -proot -e "SELECT COUNT(*) FROM db_pg.pwork; SELECT COUNT(*) FROM db_pg.user;"
```

> 脚本里的 `DELIMITER` 语法只有 mysql 客户端认识；**不要**改用 JDBC/其它驱动执行 V001。

## 7. 交回

`_Outputs/待审核/C00/<run_id>/`：`summary.json` + `结论.md`（≤150 行）+ `details/`（两次执行日志、verify 输出、`db_pg` 前后行数对比）。
结论必须写：**有没有脏值需要人工决定**、**哪些断言过了/没过**、**回退命令一行**。

## 8. 关闭检查

- [ ] V001 连跑两遍均 0 错误
- [ ] verify_V001.sql 全部断言通过（输出里能逐条看到）
- [ ] `db_pg` 的 `pwork/user/comment/liketable` 行数与备份前一致（用 SQL 证明）
- [ ] 触发器数 17 → **15**（只删 liketable 两个）
- [ ] 计数字段类型为 `int unsigned`
- [ ] 无真实手机号残留（`SELECT pnum FROM user WHERE pnum REGEXP '^1[3-9][0-9]{9}$' AND pnum NOT LIKE '1380000000%'` 返回 0 行）
- [ ] 未改 `src/**`、未做 git 操作
