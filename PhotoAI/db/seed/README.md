# 演示数据快照

`demo_snapshot.sql` 是本地开发库 `db_pg` 的完整快照（2026-10-24），用来一键起一个**有内容的**演示环境，
避免拉下仓库后面对空列表无法验证功能。

## 内容规模

| 表 | 行数 |
|---|---|
| user | 300（1 个管理员 + 299 个演示用户/摄影师） |
| pwork | 57（其中 46 件「展示」，另有待审核/隐藏/已驳回若干） |
| liketable | 5935（`targettype='帖子'`，与 `pwork.likep` 完全一致） |
| comment / favorite / follow | 见快照内实际行数 |

## 怎么用

```bash
# 1) 先建结构（迁移管结构）
mysql -u root -p -e "CREATE DATABASE db_pg DEFAULT CHARACTER SET utf8mb4;"
mysql -u root -p db_pg < db/migration/backup_before_V001.sql
for f in db/migration/V00*.sql; do mysql -u root -p db_pg < "$f"; done

# 2) 再灌演示数据（快照管数据）
mysql -u root -p db_pg < db/seed/demo_snapshot.sql
```

PowerShell 下 `>` 会生成 UTF-16，导入请用 `mysql --result-file` 或 `source` 命令。

## 演示账号

| 用途 | 手机号 | 密码 |
|---|---|---|
| 后台管理员 | `13900000001` | 本地开发口令，**不写入仓库**；见本机 `Backend/.env` 或向项目负责人索取 |

> 上线/公网演示前必须修改这个默认口令，否则等同公开后台入口。

后台登录页：`http://localhost:8082/login/page`（只能用管理员登录）。

演示摄影师（`d1`~`d6`、`u1`~`u2`、`du001` 起 240 个）没有可用密码，走小程序的 mock 登录
（`WX_MOCK_ENABLED=true` + `dev` profile + `code` 以 `mock:` 开头）。

## 注意

- **这个快照只用于本地开发与演示**，不要用于生产环境。
- 快照里包含用户表的密码哈希（BCrypt），是上述演示账号的；正式环境请自行创建管理员并改密。
- 结构与数据分开管：表结构一律改 `db/migration/V00x__*.sql`，**不要**用快照去充当迁移。
- 快照是某一时刻的切片，后续如果加了列/表，需要重新导出并同步本文件。
