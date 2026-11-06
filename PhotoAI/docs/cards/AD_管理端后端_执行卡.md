# AD 管理端后端｜执行卡（一揽子）

状态：**待终审**，未派工。范围：管理端全部后端改动（原 AD1+AD2+AD3 的后端部分合并）。对应 F-11、A-14。
岗位：实现 = codex（`gpt-6.1-sol`+`high`）；测试 = Hermes 写并冻结后交实现岗只读；验收 = Hermes 复跑 + 用户视觉/操作审核。
**模板与前端资源（`templates/**`、`static/admin/**`）不在本卡范围，由 Hermes 交付，codex 只读不写。**

## 目标

用一次实现补齐管理端六项能力，使重做后的管理页面（概览/审核/作品/用户）能全部跑真数据：
`GET /admin/stats`、`GET /admin/work/{pid}`、`GET /admin/work/list`、`PUT /admin/work/{pid}/status`、`GET /admin/user/guards`、`PageController` 三条页面路由。

## 契约（权威＝docs/接口增补_ADMIN.md，逐字执行，不得自行调整字段名/参数/错误码）

| 接口 | 要点 |
|---|---|
| `GET /admin/stats` | data 恰七键：`pendingCount,todayApprovedCount,userCount,workCount,statusCounts{四键},categoryCounts[],recentPending[]`；同一只读事务；`pending==statusCounts.pending`，四项和与分类总和均 `==workCount` |
| `GET /admin/work/{pid}` | data 恰 `work/exif/author`；任意状态可查；不增浏览；`author` 不含手机号/openid/pwd |
| `GET /admin/work/list` | 参数 `status/keyword/category/page/size`；`status` 非法值 400 不降级；`AdminWork` 恰 17 键；排序 `cwtime DESC,pid ASC`；data 恰 `{list,total}` |
| `PUT /admin/work/{pid}/status` | body 仅 `展示/隐藏`；展示↔隐藏 200 不写日志；`已驳回→展示` 200 且同事务追加唯一 `waudit(act='pass',reason=NULL)`；`已驳回→隐藏`、`待审核→*` 409 |
| `GET /admin/user/guards` | `uids` 逗号分隔 1–50 个；每项恰 5 键；优先级 `self>last_admin>has_works`；不返回作品数 |
| 页面路由 | `/page/admin/works`、`/page/admin/users` 加管理员注解；旧管理入口管理员访问 302 到新页 |

硬约束：`AuditApiTest` 的 `ADMIN_USER_KEYS`/`aud_13`/`assertKeys` 严格八键，**禁止改测试、禁止给 AdminUser 加字段**；不得复用或放宽 `PUT /pwork/{pid}/status`（作者限定），不得改 `AuditService/Impl` 既有方法签名。

## 可写白名单（逐文件；未列即禁写）

| 文件 | 允许范围 |
|---|---|
| `Backend/src/main/java/com/pg/controller/AdminStatsController.java` | 新增，类级 `@RequiresRole("管理员")`，只暴露 stats |
| `Backend/src/main/java/com/pg/service/AdminStatsService.java` | 新增接口 `Map<String,Object> stats()` |
| `Backend/src/main/java/com/pg/service/impl/AdminStatsServiceImpl.java` | 新增只读聚合、显式投影、事务标注 |
| `Backend/src/main/java/com/pg/mapper/AdminStatsMapper.java` | 新增只读查询声明 |
| `Backend/src/main/resources/mapper/AdminStatsMapper.xml` | 新增聚合 SQL，禁 `SELECT *` |
| `Backend/src/main/java/com/pg/controller/AdminWorkController.java` | 仅增加 `list`、`detail`、`status` 三个方法及所需构造器依赖；不动既有 pending/approve/reject |
| `Backend/src/main/java/com/pg/service/AdminWorkService.java` | 新增三个方法声明 |
| `Backend/src/main/java/com/pg/service/impl/AdminWorkServiceImpl.java` | 新增查询/详情/状态事务；不复用发布流程 |
| `Backend/src/main/java/com/pg/mapper/AdminWorkMapper.java` | 新增 list/count/detail 声明 |
| `Backend/src/main/resources/mapper/AdminWorkMapper.xml` | 新增同条件 list/count、详情与最近 reason 投影 |
| `Backend/src/main/java/com/pg/mapper/AuditMapper.java` | 仅新增状态行锁读取与非待审条件更新两个方法 |
| `Backend/src/main/resources/mapper/AuditMapper.xml` | 仅新增对应两条 statement，不改 pending/updatePending/insertAudit |
| `Backend/src/main/java/com/pg/controller/AdminUserController.java` | 仅增加 `guards` 方法 |
| `Backend/src/main/java/com/pg/service/AdminUserService.java` | 仅增 `guards(...)` 声明 |
| `Backend/src/main/java/com/pg/service/impl/AdminUserServiceImpl.java` | 仅增只读 guards 投影与入参校验，不改 list/changeStatus/delete |
| `Backend/src/main/java/com/pg/mapper/AdminUserMapper.java` | 仅增 guards 只读查询声明 |
| `Backend/src/main/resources/mapper/AdminUserMapper.xml` | 仅增 guards 相关 SQL |
| `Backend/src/main/java/com/pg/controller/PageController.java` | 仅新增两条页面路由与旧入口 302；不改登录/普通用户路由 |

其余全部只读：`PworkController/Service/Mapper`、`AuditService/Impl`、`AuthInterceptor`、`Pom`、`application*.yml`、所有测试与冻结文档。**无 DDL/迁移授权**（不新增表、列、索引）。

## GWT 验收

| 组 | Given / When / Then |
|---|---|
| G1 聚合自洽 | 给定四状态作品、禁用用户、零件分类与未分类；GET stats；七键齐全、类型为整数、`pending==statusCounts.pending`、四状态和==`workCount`、分类总和==`workCount`、`recentPending` ≤3 且 `cwtime DESC,pid ASC`；空库全部 0 且 `categoryCounts` 至少含"未分类"一项或按字典项零值 |
| G2 今日通过 | 给定昨日 23:59:59、今日 00:00、今日 23:59:59、明日 00:00 的 pass/reject 与同 pid 重复 pass；只计区间内 pass 且按 pid 去重；今日通过后作品被隐藏仍计入；reject 不计 |
| G3 列表与详情 | 四状态+同名分类+标题/作者/pid 关键词组合查询；`list`/`total` 同条件、超页空列表、`status` 非法 400 不降级、`pid` 格式 400、`/pending` 与 `/list` 不被 `/{pid}` 吞；详情 17 键齐、exif/author 结构正确、不增 `viewcount` |
| G4 状态矩阵 | 8 格全覆盖：展示↔隐藏 200 无日志；同态幂等无写入；已驳回→展示 200 且 `waudit` 恰 +1 条 pass 且成为最近记录；已驳回→隐藏 409；待审核→展示/隐藏 409；日志插入失败整笔回滚；并发恢复只 1 条 pass |
| G5 保护 | guards 覆盖自己/最后正常管理员/有任意状态作品/可删普通用户；优先级 `self>last_admin>has_works`；不存在 uid 404 整次失败；重复/超 50/非法 400；列表页仍八键 |
| G6 权限信封 | 匿名 401、普通用户 403、禁用主体 401、坏头+好 Cookie 401（不静默回退）、非法枚举 400，全部 JSON 信封不重定向；管理页未授权仍 302；方法不支持 405/invalid_request |
| G7 只读性 | 记录 user/pwork/waudit/浏览计数快照，连续多次 stats/list/detail/guards；四类读接口零写入，`viewcount` 不变 |
| G8 回归 | 既有 `AuditApiTest` 全绿且未被修改（比对 SHA256），`mvn8 -B -ntp test` 全量无新增红 |

## 禁止行为

写测试、改测试、改 POM/依赖、改拦截器、改既有冻结接口、新增状态/act/角色、加缓存、加索引/迁移、执行 git、顺手改文档或模板、用 `SKIP`/特判环境求绿。

## 真跑

```bash
export JAVA_HOME=/c/develop-tools/JDK/jdk1.8.0_504
export PATH="$JAVA_HOME/bin:$PATH"
cd "C:/work/dev/projects/PhotoAI Hub/Backend"
java -version
mvn8 -B -ntp -Dtest=AdminApiTest test
mvn8 -B -ntp -Dtest=AuditApiTest test
mvn8 -B -ntp test
```
每条命令立即记录退出码与 `Tests run/Failures/Errors/Skipped`；失败先归因（实现缺口 / 测试缺陷 / 环境）再动手，`Errors>0` 先修骨架不放行。

## 交回

改动文件清单 + 每个接口的原始 curl 或测试输出 + 全量结果 + 未验证项 + 不确定点。产出 UTF-8 无 BOM。禁止 git。
