---
卡号: C00-T
类型: 测试卡（验收断言，不写实现）
项目: PhotoAI Hub
模块: DB baseline
状态: 已终审
配套: docs/cards/C00_DB迁移_执行卡.md
---

# C00-T 数据库迁移 · 验收断言

> 全部断言只在 **`db_pg_mig_test`** 上跑（`db_pg` 只允许读）。
> 交付物必须包含 `db/migration/verify_V001.sql`，逐条断言并打印 `PASS/FAIL`；任一 FAIL 即整卡不通过。

## 1. 断言清单（12 条）

| # | 断言 | SQL（返回期望） |
|:-:|---|---|
| A1 | 幂等：连跑两遍 | 两次执行退出码均 0（不是靠 `IF EXISTS` 吞错的日志刷屏） |
| A2 | 计数列类型 | `SELECT data_type FROM information_schema.columns WHERE table_schema='db_pg_mig_test' AND table_name='pwork' AND column_name IN ('viewcount','votecount','likep')` → 3 行全是 `int` |
| A3 | 其余计数列 | 同上查 `comment.likecom`、`pcatogory.pc_count`、`gift.stocknum`、`gift.gneed`、`user.vote` → 全 `int` |
| A4 | 点赞唯一索引 | `SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='db_pg_mig_test' AND table_name='liketable' AND index_name='uk_uid_target'` → `≥1` |
| A5 | 无重复点赞 | `SELECT COUNT(*) FROM (SELECT uid,targetid,targettype FROM liketable GROUP BY 1,2,3 HAVING COUNT(*)>1) t` → `0` |
| A6 | 触发器数量与名单 | 总数 → `15`；`tri_liketable_after_insert`/`after_delete` → 0 行；`tri_vote_after_insert`/`after_delete` → 各 1 行 |
| A7 | user 新列 | `openid`/`bio` 存在；`pwd` 的 `is_nullable='YES'`；`openid` 上有唯一索引 |
| A8 | pwork 枚举与默认值 | `column_type` 含 `待审核`/`展示`/`隐藏`/`已驳回`；`column_default='待审核'`；存量 9 行 `wstatus='展示'` |
| A9 | 新增索引 | `pwork` 有 `(uid,cwtime)`、`(wstatus,cwtime)`；`comment` 有 `(pid,comct)`；`sys_log` 有 `(createtime)` |
| A10 | 外键 | `information_schema.referential_constraints` 计数 → `9`；且**没有**指向 `liketable.targetid` 的外键 |
| A11 | 视图去 cast | `SELECT view_definition FROM information_schema.views WHERE table_name='view_work_hot'` → **不含** `cast(`, 含 `order by` |
| A12 | 手机号脱敏 | `SELECT COUNT(*) FROM user WHERE pnum REGEXP '^1[3-9][0-9]{9}$' AND pnum NOT LIKE '1380000000%'` → `0` |

## 2. 行数一致性（防误删）

> **基线以实库为准，不以 `photoai.sql` 为准**：dump 是 2026-06-22 的旧快照，实测 `db_pg` 与它**有差异**（例：`orderinfo` 实库 **4** 行，dump 里 5 条 INSERT）。
> 做法：迁移前把 6 张表的 `COUNT(*)` 写进 `details/counts_before.json`，迁移后逐表比对，**必须完全相同**（`liketable` 允许因去重减少）。

| 表 | 参考值（实库实测，2026-10-18） | 判定 |
|---|---|---|
| `pwork` | 9 | 前后一致 |
| `user` | 10 | 前后一致（脱敏只改值） |
| `comment` | 3 | 前后一致 |
| `liketable` | 10 | 迁移后 **≤10**（去重），且 A5 通过 |
| `orderinfo` | **4** | 前后一致 |
| `gift` | 2 | 前后一致 |

> 若迁移后任一表行数与 `counts_before.json` 不符 → 立即停，贴出 `SELECT COUNT(*)` 原始输出，**不许"修数据凑数"**。
> 若实测与上表参考值不同 → **以 `counts_before.json` 为准**，不要停下来（差异本身不是阻塞条件，除非迁移导致变化）。

## 3. 负向检查（必须为 0）

```sql
-- 孤儿行（建 FK 前提，迁移后也应为 0）
SELECT COUNT(*) FROM favorite f LEFT JOIN pwork p ON f.pid=p.pid WHERE p.pid IS NULL;   -- 0
SELECT COUNT(*) FROM comment  c LEFT JOIN pwork p ON c.pid=p.pid WHERE p.pid IS NULL;   -- 0
SELECT COUNT(*) FROM liketable l LEFT JOIN user u ON l.uid=u.uid WHERE u.uid IS NULL;   -- 0
-- 计数为负（改 INT 后应无符号）
SELECT COUNT(*) FROM pwork WHERE likep < 0 OR votecount < 0;                            -- 0
```

## 4. 生产库未被动过（只读核对）

```sql
SELECT COUNT(*) FROM db_pg.pwork;       -- 必须仍为 9
SELECT COUNT(*) FROM db_pg.user;        -- 必须仍为 10
SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema='db_pg';  -- 必须仍为 17
```

> 三条任一变化 → **越界**（改了生产库），整卡不通过并暂停该路线。

## 5. 交付时附

- `details/run1.log`、`details/run2.log`（两次执行）
- `details/verify.log`（12 条断言的 PASS/FAIL 逐条输出）
- `details/counts_before_after.txt`（6 张表的前后行数）
- `db/migration/README.md`：**一行回退命令**，例如
  `mysql -uroot -proot -e "DROP DATABASE db_pg_mig_test" && mysql -uroot -proot db_pg < db/migration/backup_before_V001.sql`
