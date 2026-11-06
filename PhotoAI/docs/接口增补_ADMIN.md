# 管理端接口增补（ADMIN v1）

状态：随 `docs/cards/AD_管理端后端_执行卡.md` 一并冻结。本文件是契约权威；卡面只写键名与口径指针，两者冲突以本文件为准。
依据：docs/接口冻结_v1.md §2 公共信封与 §七 命名风格；本文件只**新增**接口，不改动已冻结 #14–#19、#9b、#10 的任何行为。

## 公共约定

- 顶层恰 `code/msg/data`；成功 200 且 `code='ok'`，失败 `data=null`：400 `invalid_request`、401 `unauthorized`、403 `forbidden`、404 `not_found`、405 `invalid_request`、409 `conflict`、500 `internal_error`。
- 全部新接口类级 `@RequiresRole("管理员")`；沿用既有拦截器（JWT claim 定角色、每请求查库主体状态、头优先于 Cookie）。API 失败返 JSON **不重定向**；`/page/admin/*` 既有 302 规则不变。
- 类型：`N`=非负 JSON 整数（Java `Long`）；`ID`=字符串；`T`=`yyyy-MM-dd'T'HH:mm:ssXXX`（+08:00）；声明 nullable 的键**必须存在**。
- 一律 `private final` 构造器注入、`BizException` 抛错、显式 `Map` 投影、中文 Javadoc、UTF-8/CRLF；禁止 `SELECT *`、禁止字符串拼 SQL。

## 1. GET /admin/stats

仅管理员、无参数、只读。`data` 恰七键：

| 字段 | 精确口径 |
|---|---|
| `pendingCount:N` | 当前 `pwork.wstatus='待审核'` 数量 |
| `todayApprovedCount:N` | 一次请求内取服务器 `ZoneId.systemDefault()` 的当日 `[00:00, 次日00:00)`；`waudit.ct` 落区间且 `act='pass'`，`COUNT(DISTINCT pid)`。同件多次通过只计一次；作品后来被隐藏仍计入。 |
| `userCount:N` | `user` 全表，含禁用 |
| `workCount:N` | `pwork` 四状态合计，不含已删除 |
| `statusCounts:object` | 恰 `pending/visible/hidden/rejected:N`，对应待审核/展示/隐藏/已驳回，缺项补 0；`pending==pendingCount`，四项和 `==workCount` |
| `categoryCounts:array` | 每项恰 `pcid:ID?、pcname:string、count:N`；全状态按 pcid 分组，`pclist` 字典项含 0 件；按 `pclist.sort ASC, pcid ASC`；末尾固定追加 `{pcid:null, pcname:"未分类", count:N}` 计 pcid 为空或无法关联字典的历史行；总和 `==workCount` |
| `recentPending:array` | ≤3 件当前待审核，`cwtime DESC, pid ASC`；每项恰 `pid,uid,wtitle,vurl,uname,pcname,cwtime,wstatus`，类型/null 同冻结 Work；空则 `[]` |

同一只读事务内完成（`REPEATABLE_READ` 快照），总量与分布必须自洽。时区边界在 Java 侧算好再参数绑定。
**索引与缓存裁决：本版都不加。** 不新增 DDL、不加 Redis/本地缓存；隔离库跑 `EXPLAIN` 与实际耗时留证，不声称性能达标。

## 2. GET /admin/work/{pid}

仅管理员、只读，可查任意四状态。`pid` 严格 `[A-Za-z0-9_-]{1,36}`，格式不符 400，合法不存在 404。
`data` 恰 `work / exif / author`：

- `work`：同上表 17 键中的作品字段集（`pid,uid,wtitle,wdesc,vurl,viewcount,votecount,wstatus,cwtime,likep,uname,avatar,pcid,pcname,realLikeCount,favoriteCount,reason`）
- `exif`：与冻结 EXIF 六项同构，无记录时各值为 null（键仍在）
- `author`：`uid,nickname,avatar`（不返回手机号、openid、pwd）

**不得**复用公开 `GET /pwork/{pid}`：那个对他人待审 404，且会自增浏览。新详情任何状态都不增浏览、不触发 EXIF 解析或发布流程。
路由注意：静态 `/admin/work/pending` 与 `/admin/work/list` 必须优先于 `/{pid}`，不得被动态段吞掉。

## 3. GET /admin/work/list

| 参数 | 精确语义 |
|---|---|
| `status:string` | 省略=全部；存在时仅 `待审核/展示/隐藏/已驳回`；空串、空白、带首尾空格、其它值一律 400，**不 trim、不降级为全部** |
| `keyword:string` | 选填，≤50 Unicode 字符；省略或空串无筛选，其余不 trim；匹配 `wtitle` 或 `user.uname` 字面子串，或 `pid` 精确匹配；LIKE 的 `%`/`_`/转义符按字面处理，参数绑定 |
| `category:string` | 选填，即 pcid；省略无筛选；传入必须 `[A-Za-z0-9_-]{1,36}`，空串/非法 400；格式合法但不存在 → 200 空页；**不新增"未分类"伪 ID** |
| `page:int`、`size:int` | 默认 1/10；`page≥1`、`size` 1–50；非整数/越界 400；超页 → 200 `list=[]` 且真实 `total`；**不接受** `pageNum/pageSize` 别名（传即 400） |

`data` 恰 `{list,total}`；条件 AND 组合，先过滤后分页，`list`/`count` 同条件；排序固定 `cwtime DESC, pid ASC`，无 sort 参数。
`AdminWork` 恰 17 键：`pid,uid,wtitle,wdesc,vurl,viewcount,votecount,wstatus,cwtime,likep,uname,avatar,pcid,pcname,realLikeCount,favoriteCount,reason`。前 16 键类型/null 同冻结 Work（无 `favorited`）；`reason:string|null` = 该件 `waudit` 按 `ct DESC, aid DESC` 最近一条：reject 取原文，pass 或无日志为 null。读查询不写状态、不增浏览。

## 4. PUT /admin/work/{pid}/status

**不能复用** `PUT /pwork/{pid}/status`：冻结 #9b 限定 `uid=作者`，管理员操作他人作品会 404。作者接口不得放宽。
body 必须是 JSON 对象，`status:string` 必填且仅 `展示`/`隐藏`，无 trim、无类型转换；缺失/null/待审核/已驳回/其它类型 → 400。成功 `data` 恰 `{pid,status}`；合法不存在 404。

| 当前 → 目标 | 结果 |
|---|---|
| 展示→隐藏、隐藏→展示 | 200 更新，仅可见性变化，**不追加**审核日志 |
| 展示→展示、隐藏→隐藏 | 200 幂等，无写入、无日志 |
| 已驳回→展示 | 200；同事务追加唯一 `waudit(act='pass', reason=NULL, uadmin=已验uid, ct=现有审计时间口径)`；之后 mine 的最接近理由为 null |
| 已驳回→隐藏 | 409，不隐式恢复、不藏起审核结论 |
| 待审核→展示/隐藏 | 409，必须走 #15/#16，不绕审核 |

写入只由 `AuditMapper` 新增方法承担（同事务先锁读再条件更新）；不写其它 Mapper 的状态。日志插入失败整笔回滚；并发恢复只产生 1 条 pass，后到者按展示幂等 200。新 pass 必须在 `ct DESC, aid DESC` 下成为最近记录（同秒旧 reject 也不得残留为 mine 理由）。只改 `wstatus`，禁止改内容/计数，禁止调用发布、编辑、安全检查、EXIF、向量流程，不新增状态/act/角色。

## 5. GET /admin/user/guards

**不向 AdminUser 加 workCount**：`AuditApiTest` 的 `ADMIN_USER_KEYS` / `aud_13` / `assertKeys` 严格断言八键，禁止改弱或改测试。#17–#19 保持原实现与参数口径不变。

`uids` 必填，逗号分隔，1–50 个不重复 uid，每项 `[A-Za-z0-9_-]{1,64}`；空项/重复/超量/非法 400；任一合法 ID 不存在 404 且整次 `data=null`。
`data` 恰 `{list:[...]}`，顺序同请求；每项恰六个键：`uid:ID, canDisable:boolean, canDelete:boolean, disableReason:string|null, deleteReason:string|null, workCount:N`。
`workCount` = 该用户名下四状态作品总数（供删除前提示波及范围），**不放进 AdminUser 列表**。

判定优先级 `self > last_admin > has_works`，同一只读快照内按库内真实数据算：

1. 是本人 → 两权限 false，reason `self`
2. 否则目标 `role='管理员'` 且 `status='正常'` 且全库正常管理员数 == 1 → 两权限 false，reason `last_admin`
3. 否则 `canDisable=true/disableReason=null`；`workCount>0` → `canDelete=false/deleteReason=has_works`，否则 true/null

它只描述这三项保护，不是写授权凭证；无缓存、无新索引；按本页最多 50 个 uid 批量查，禁止逐行 N+1。

## 5.1 DELETE /admin/user/{uid}（v2 变更：可选级联）

v1（卡04 #19）行为保持不变：**不带 `cascade` 时，有任何状态作品的用户一律 409 `conflict`**，不删任何数据。
新增查询参数 `cascade`：仅接受字面量 `true`；省略、`false`、其它值都按 v1 处理（有作品 409），**不做类型转换、不 trim**。

`cascade=true` 时，在同一条事务内按依赖顺序删除（全部无外键，必须显式清理，禁止依赖级联）：

| 顺序 | 表 | 条件 |
|---|---|---|
| 1 | `comment` | `pid IN (该用户作品)` 或 `uid = 目标 uid` |
| 2 | `favorite` | `pid IN (该用户作品)` 或 `uid = 目标 uid` |
| 3 | `liketable` | `(targetid IN (该用户作品) AND targettype='帖子')` 或 `uid = 目标 uid` |
| 4 | `vote` | `pid IN (该用户作品)` 或 `uid = 目标 uid` |
| 5 | `pcatogory` | `pid IN (该用户作品)` |
| 6 | `work_embedding` | `pid IN (该用户作品)` |
| 7 | `work_exif` | `pid IN (该用户作品)` |
| 8 | `waudit` | `pid IN (该用户作品)` 或 `uadmin = 目标 uid` |
| 9 | `follow` | `uid = 目标 uid` 或 `tuid = 目标 uid` |
| 10 | `pwork` | `uid = 目标 uid` |
| 11 | `gift` | `uid = 目标 uid`（历史死业务，随用户清理） |
| 12 | `orderinfo` | `uid = 目标 uid`（历史死业务，随用户清理） |
| 13 | `user` | `uid = 目标 uid` |

约束：
- 仍禁止删自己（403 `forbidden`）、禁止删最后一个正常管理员（409 `conflict`）；合法不存在 404。
- 成功 `data`：**不带 `cascade` 时保持 v1 的 `data=null`**（既有 `AuditApiTest` 冻结断言依赖这一点，不得改测试）；`cascade=true` 时 `data` 恰 `{uid:ID, deletedWorks:N}`，N 为实际删除的作品行数。
- **`orderinfo` / `gift` 纳入级联（第 12、13 张）**：订单与礼物功能已确定不做，属历史死业务，删用户时一并清理该用户行；两表无外键，清理顺序放在最后（在删 `user` 之前）。
- 任一语句失败整笔回滚，用户与作品保持原状；日志不得打印手机号/密码。
- 禁止删除被本清单以外表引用的记录后留下悬挂行：若发现清单外的关联表，先报告再决定，**不得擅自扩大删除范围**。
- 前端在 `workCount>0` 时必须以「将一并删除 N 件作品」二次确认后才带 `cascade=true`；`workCount=0` 走普通删除（不带参数）。

## 5.2 禁用用户的作品联动（v2.1）

`PUT /admin/user/{uid}/status`，当 `status='禁用'` 生效时，**同一事务内**执行：

- `UPDATE pwork SET wstatus='隐藏' WHERE uid=<目标> AND wstatus='展示'`
- 只改 `展示`；`待审核` / `隐藏` / `已驳回` 保持原状
- 解禁（`status='正常'`）**不自动恢复**作品，由管理员在作品管理里逐件"恢复展示"
- 成功返回仍为 `{uid,status}`（不加字段，避免破坏既有断言）

## 5.3 审核通过遇到被禁用作者（v2.1）

`PUT /admin/work/{pid}/approve` 在判定前检查作者状态：

- 作者 `user.status = '正常'` → 原逻辑不变
- 作者非正常（禁用或不存在）→ **自动驳回**：同事务把作品改为 `已驳回` 并写唯一 `waudit(act='reject', reason='作者账号已被禁用', uadmin=<当前管理员>, ct=现有审计时间口径>)`，然后返回 **409**，业务码为新增的 **`author_disabled`**（HTTP 409，固定文案「作者账号已被禁用，已自动驳回」）。新增码是为了让客户端能稳定区分"作者禁用自动驳回"与普通 `conflict`（非待审核）；全局异常处理器只回传错误码的固定文案，不接受自定义 message。
- 作品原本不在 `待审核` → 仍按既有 409「非待审核」处理，不写日志
- 前端收到该 409 时展示后端 `msg`（不再用通用文案），并重读队列

## 7. 封禁申诉（v3，2026-11-06 用户拍板方案 A）

封禁采用「登录直接拦截」，因此申诉入口**免登录**，但身份必须可信：用 `wx.login` 的 code 换 openid，确认「就是你本人」且「确实处于禁用状态」。

### 7.1 POST /appeal（匿名）

- body：JSON `{code:string, content:string}`
- `code` 走与 `/auth/wxlogin` **相同**的 code2session 路径（dev + `mock:` 前缀的 mock 仅在该条件下可用，生产禁用）；code 无效 → 401 `unauthorized`
- `content`：必填，1–200 个 Unicode 字符，不得全为空白；不裁剪原文；超长/空白 → 400 `invalid_request`
- openid 查不到用户 → **400 `invalid_request`**（与"未封禁"共用文案「账号不存在或无需申诉」；**不用 404**——否则外部可以靠状态码探测某个微信账号是否注册过）
- 用户状态为「正常」→ 400 `invalid_request`，文案同上
- 同一用户 24 小时内已有「待处理」申诉 → 409 `conflict`「已有一条待处理申诉」
- 成功：200 `{aid:ID, status:"待处理"}`
- 同一 openid 每分钟最多 1 次提交（用 `appeal` 表按 ct 计数实现，不引入 Redis）

### 7.2 GET /admin/appeal/list（管理员）

- Q：`status`（选填，仅 待处理/已受理/已驳回，其它值 400）、`page`、`size`（默认 1/10，size 1–50）
- data 恰 `{list,total}`；每项恰 `aid,uid,nickname,avatar,content,status,ct,handler,hct,reason`
- 排序 `ct DESC, aid DESC`；`nickname` 取 user.uname，用户已删则为 null

### 7.3 PUT /admin/appeal/{aid}/handle（管理员）

- body：`{result:"接受"|"驳回", reason?:string}`
- 「接受」：**同一事务**把 `appeal.status='已受理'`、写 handler/hct，并把该用户 `user.status` 改回「正常」；作品**不自动恢复展示**
- 「驳回」：`status='已驳回'`，`reason` 必填 1–200 字，写 handler/hct
- 申诉不存在 → 404；已处理（非「待处理」）→ 409 `conflict`
- 成功 data 恰 `{aid,status}`

### 7.4 登录侧（v3 变更）

被封禁用户登录返回 **403 `account_disabled`**（「账号已被封禁」），与"密码错误/账号不存在"的 401 区分开；小程序据 `code` 显示封禁页并给申诉入口。

## 8. 分类管理（v3，2026-11-06 用户要求）

现状只有只读 `GET /category/list`；新增一个写入口，够管理端"加一个分类"即可，**不做改名/删除**（本期无需求，避免误删作品分类关系）。

### 8.1 POST /admin/category（管理员）

- body：JSON `{pcname:string, cds?:string}`
- `pcname`：必填，1–20 个 Unicode 字符，不得全为空白；**不 trim**，按原文存储；与现有分类重名（字面完全一致）→ 409 `conflict`「分类已存在」
- `cds`：选填，≤255 字符，仅作说明
- `pcid`：服务端生成 36 位（UUID 去横线），不接受客户端传入；客户端传了也忽略
- `sort`：取当前 `MAX(sort)+10`，同一事务内计算，避免并发撞号
- 成功：200 `data` 恰 `{pcid:ID, pcname:string, sort:N}`
- 匿名 401、非管理员 403、body 非法 400

### 8.2 与既有接口的关系

- `GET /category/list` 行为不变（公开、按 sort 升序），新增分类自动出现在列表与作品管理的分类下拉里
- 分类被作品引用后不允许删除；本期不提供删除入口

## 6. 页面路由（后端侧最小改动）

`PageController` 仅新增：`/page/admin/works` → `pwork/pworkList`、`/page/admin/users` → `user/userList`，与既有 `/page/admin/index`、`/page/admin/audit` 一样加管理员注解。旧入口 `/pwork/list/page`、`/view/workdetail/list/page` 在管理员访问时 302 到对应新管理页；普通用户路由不动。**模板文件本身由 Hermes 交付，codex 不写不读改。**
