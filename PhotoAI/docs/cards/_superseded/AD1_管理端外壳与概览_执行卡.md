# AD1 管理端外壳与概览｜执行卡

状态：待终审，仅出卡，未派工。范围：C / M1；对应 F-11、A-14。

## 目标、输入与依赖

重做 `/page/admin/index`，建立无 iframe 的统一侧栏、顶栏与概览。输入为全仓只读；白名单只约束写入。必读需求 F-11、冻结 §1.1/1.4/2/6 U5/U7/U8/七、代码风格、岗位分工、04 卡对及 `docs/preview/admin_mockup.html`。文档冲突以冻结 §七为准：构造器注入、private final、BizException、显式 Map、字符串码、中文 Javadoc、UTF-8/CRLF。

前置：V002 及现有 04 审核闭环可用；独立 T 岗先交 AD1 测试并冻结，I 岗只读测试。AD1 先于 AD2/AD3；后两卡分别将审核、作品/用户模板接入 AD1 外壳。AD1 的单卡验收不冒称子页已改造，三卡装配后再验全站统一壳。

## 可写白名单（逐文件）

以下仅供未来实现；本轮只新增卡。

| 文件 | 允许范围 |
|---|---|
| `Backend/src/main/java/com/pg/controller/PageController.java` | 仅管理页路由：保留 index/audit；新增 `/page/admin/works`、`/page/admin/users` 返回原 pwork/pworkList、user/userList；index/新管理方法加管理员注解；已授权访问旧 userList/pworkList/workDetailList 页时重定向至对应新管理页，作品视图并入作品管理；不改登录、普通用户主页、用户新增编辑路由 |
| `Backend/src/main/resources/templates/admin/index.html` | 独占重写概览，删除旧 iframe/tab/个人资料 iframe，导航仅概览、审核、作品、用户 |
| `Backend/src/main/resources/templates/admin/layout.html` | 新增唯一公共 Thymeleaf 外壳 |
| `Backend/src/main/resources/static/admin/admin-shell.css` | 新增公共颜色、侧栏、顶栏、表格/按钮基础样式 |
| `Backend/src/main/resources/static/admin/admin-api.js` | 新增统一请求与权限提示 |
| `Backend/src/main/resources/static/admin/admin-dashboard.js` | 新增概览渲染与刷新 |
| `Backend/src/main/java/com/pg/controller/AdminStatsController.java` | 新增，类级 RequiresRole 管理员 |
| `Backend/src/main/java/com/pg/service/AdminStatsService.java` | 新增接口 `Map<String,Object> stats()` |
| `Backend/src/main/java/com/pg/service/impl/AdminStatsServiceImpl.java` | 新增只读聚合与显式投影 |
| `Backend/src/main/java/com/pg/mapper/AdminStatsMapper.java` | 新增只读查询接口 |
| `Backend/src/main/resources/mapper/AdminStatsMapper.xml` | 新增聚合 SQL，禁止 SELECT * |
| `docs/接口增补_AD1.md` | 新增，本卡获终审后原样记录下述增补，不修改旧冻结文档 |

旧 `admin/audit.html` 归 AD2；旧 user/pwork 模板归 AD3；旧 `view/workDetailList.html` 只读保留，入口在 PageController 收口，不再进入新导航。三卡不重复持有文件。

## 接口契约与技术裁决（本节待终审，批准即冻结增补）

`GET /admin/stats`：无参数、仅管理员、只读；200/ok，data 恰为以下七键。N=非负 JSON integer，Java Long；ID=string；nullable 键必须存在。时间 T 按冻结 §2 输出 `yyyy-MM-dd'T'HH:mm:ssXXX`、Asia/Shanghai。

| 字段 | 精确口径 |
|---|---|
| `pendingCount:N` | 当前 pwork.wstatus=待审核的作品数 |
| `todayApprovedCount:N` | 一次请求捕获服务器 `ZoneId.systemDefault()` 的日期，区间 `[当日00:00,次日00:00)`；查 waudit.ct 在区间内且 act='pass'，COUNT(DISTINCT pid)。同件重复送审通过只计一件；不按当前展示数、不查 cwtime；隐藏后的作品及仍保留的历史日志仍计入。AD3 的已驳回恢复通过日志也计入 |
| `userCount:N` | user 全部账号，含禁用 |
| `workCount:N` | pwork 当前全部四状态作品，不含已删除 |
| `statusCounts:object` | 恰为 `pending/visible/hidden/rejected:N`，依次对应待审核/展示/隐藏/已驳回；缺项补0；pending=pendingCount，四项和=workCount |
| `categoryCounts:array` | 每项恰 `pcid:ID?,pcname:string,count:N`；全状态作品按 pcid 分组，所有 pclist 字典项含零件分类；按 pclist.sort ASC、pcid ASC；末尾固定追加 `{pcid:null,pcname:"未分类",count:N}`，计 pcid=NULL 及无法关联字典的历史记录；总和=workCount |
| `recentPending:array` | 最多3件当前待审核，`cwtime DESC,pid ASC`；每项恰 `pid,uid,wtitle,vurl,uname,pcname,cwtime,wstatus`，类型/null 同冻结 Work；无结果 []。不复用 #14 的 pid 升序，不增加浏览量 |

同一只读事务、显式 REPEATABLE_READ 快照聚合，避免总量与分布不一致。时区边界由 Java 计算并参数绑定；waudit.ct 是现有 DATETIME，沿现有服务器存储时区解释，不擅自迁历史时间或改全局时区。部署核验 JVM 与审核时间写入的 Asia/Shanghai 一致；测试固定 JVM 时区并验证跨日边界。此接口不加参数化时区。

**索引/缓存裁决：均不新增。** 已有 pwork `idx_status_ct(wstatus,cwtime)`、waudit `idx_pid_ct(pid,ct,aid)`；后者不声称可优化全局今日 ct 范围。V1 可接受全表只读计数，先留隔离库 EXPLAIN 与实际耗时；不捏造性能达标，不改 DDL、不加 Redis/本地统计缓存。未来确有瓶颈另卡评估 `(act,ct,pid)`，本卡不授权迁移。

所有新 API 顶层恰 `code/msg/data`；失败 data=null；401/unauthorized、403/forbidden、500/internal_error，错误不重定向。未知方法405/invalid_request。沿用统一鉴权，JWT claim 角色、每次查库状态、头优先于 Cookie，坏头不回退；不修改 AuthInterceptor。`/page/admin/*` 既有匿名/非管理员302到 `/login/page` 的规则保持。

## 外壳交接契约

公共 fragment 固定 `admin/layout :: shell(title, active, content, scripts)`；active 恰 `dash/review/works/users`，内容/脚本由页面传入 Thymeleaf fragment，不获取整页 HTML 拼装。固定挂载点 `#admin-content`、全局提示 `#admin-feedback`。AD2/AD3 各自模板调用，不复制侧栏/顶栏，不改公共文件。导航为真实链接四个 `/page/admin/*`，刷新/直达可用；无 iframe、layer type=2、innerHTML 注入页面。子页样式以各自根节点限定作用域。

共享浏览器入口固定 `window.AdminApi.request(path, {method, body}) -> Promise<data>`，method 默认 GET，body 为可选 JSON 对象；附 localStorage token 的 Bearer 头及 same-origin Cookie，仅 HTTP 2xx 且 code='ok' 成功。异常至少带 `status:number`、`code:string|null`；非 JSON/网络错误也可见。401 清本地 token/uid/role/uname 和 token Cookie，提示登录失效并提供登录链接，锁定当前页写按钮；403 提示无权限并锁定写按钮，保留凭据。API 本身不302，浏览器不将403当401。公共模块通过 `admin:auth-error` CustomEvent(detail={status,code}) 通知子页停止队列；子页不得另写鉴权设施。主动退出清同样凭据后导航登录页。

页面按确认稿侧栏216px、四 KPI、双栏分布区、最近待审表、顶栏层级还原；暖橙色固定 `#FF6B35/#FFF0EA/#FFB020/#1A1410/#5C534D/#9A8F88/#FAF8F7/#EDE8E5`。无趋势接口：原7/30天图改为四状态分布，删除同比/本周新增/最早等待的样例值及 CSV；不得展示伪数据。分类百分比=该count/workCount，零分母显示0%，整数四舍五入，仅标签可能不合100%。概览只请求一个 stats，不抓多个分页聚合；最近待审的按钮只跳 `/page/admin/audit?pid=<编码ID>`，不在概览执行审核。

## GWT 验收（6行为组）

| 组 | Given / When / Then |
|---|---|
| AD1-G1 聚合 | 给定四状态、禁用用户、零件分类与未分类；GET stats；七键、各计数和、Long 类型、最近3件与排序严格符合上表；空库零值/空最近列表 |
| AD1-G2 今日 | 给定昨日23:59:59、今日00:00、今日23:59:59、明日00:00的pass/reject及重复pid；固定当天查询；仅区间内pass去重，状态后续隐藏不影响，通过之外动作不计 |
| AD1-G3 权限 | 匿名、普通用户、禁用/删除主体、坏头+好Cookie调用；分别401、403、401、401，JSON信封；管理员Cookie可用；对应管理页面按原规则302，不泄露数据 |
| AD1-G4 只读 | 记录用户/作品/浏览/审核表快照；多次stats；零写入，单响应分布与总量一致；不触发公开详情浏览自增 |
| AD1-G5 外壳 | 管理员直达/刷新概览及四导航；唯一侧栏顶栏、激活项/标题正确、无iframe；AD2/AD3合入后四页用同一fragment，旧管理链接收口 |
| AD1-G6 页面/异常 | 1366×768及1440×900对照确认稿；暖橙布局清晰；加载/空/失败/刷新可见，401/403分流、非JSON500和网络失败不静默；不展示趋势样例，不发分页聚合请求 |

## 禁止行为、真跑与交回

不得写测试、改依赖/POM/鉴权/已有契约或其它卡文件；不得执行 git；不做 CSV、媒体自动审核、统计缓存、新角色。不得据静态字符串检查声称浏览器交互通过。

未来执行机在 Git Bash、仓库根运行；JAVA_HOME 与 mvn8 必须先核验，mvn8 不存在则查现有环境/绝对 Maven 入口后等价运行并记证据，不能跳过。隔离 MySQL8 库与密钥只经环境变量供给，不触碰生产库。

```bash
export JAVA_HOME=/c/develop-tools/JDK/jdk1.8.0_504
export PATH="$JAVA_HOME/bin:$PATH"
cd Backend
java -version
mvn8 -B -ntp -Dtest=AdminStatsApiTest,AdminShellTest test
# 每条测试命令后立即保存退出码及 Tests run/Failures/Errors/Skipped，失败先定位
node --test src/test/admin-shell.test.js
mvn8 -B -ntp -Dtest=AuditApiTest,AuthzTest,ProfileWhitelistTest,ErrorEnvelopeTest,R05SecurityTest,R05DefenseTest,IndependentR05Test test
node --test src/test/frontend-code.test.js
```

I 岗交文件范围、契约增补、定向绿日志及退出码、SQL计划/耗时、未验证项；V 岗同快照复跑并在隔离历史产出副作用后执行 `mvn8 -B -ntp test`，三卡完成再做四页浏览器验收。T→I→V分岗；全量绿不等于覆盖率达标，CI/部署分别报告。本轮上述命令、MySQL与浏览器均未运行。
