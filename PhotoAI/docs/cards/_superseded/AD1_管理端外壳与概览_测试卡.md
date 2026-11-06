# AD1 管理端外壳与概览｜测试卡

状态：待终审，仅出卡。目标：独立冻结 AD1 的聚合、外壳及权限断言；F-11/A-14，行为组6，逐项对应执行卡 AD1-G1–G6。

## 输入、依赖与可写白名单

输入=全仓只读，必读需求 F-11、冻结 §1.1/1.4/2/6/七、代码风格、岗位分工、04 卡对、视觉稿 `docs/preview/admin_mockup.html`、配对 AD1 执行卡。依赖真实隔离 MySQL8、既有迁移和鉴权。T 与 I 必须不同工人；新增目标实现不可作为断言来源，旧实现仅核入口/夹具；V 审红态后冻结，才派 I。不得修改既有 AuditApiTest 或共享基座。

仅允许新增以下文件，不允许改既有文件；白名单只限制写，不限制读：

- `Backend/src/test/java/com/pg/AdminStatsApiTest.java`：MockMvc+真实MySQL聚合/权限/只读测试；本类独立 DynamicPropertySource 指向 `db_pg_test_ad1`，自包含夹具与清理。
- `Backend/src/test/java/com/pg/AdminShellTest.java`：真实 Thymeleaf 渲染、路由与公共 fragment 装配测试。
- `Backend/src/test/admin-shell.test.js`：Node内置test/vm，实际执行 admin-api.js、admin-dashboard.js；可控 fetch/最小DOM，记录请求、提示和按钮状态，不新增依赖。

不授权写证据目录；日志经命令输出交 V 收录，构建临时产物不算源码授权。Java使用UTF-8/CRLF、构造器注入、中文注释，不修改测试收集配置。

## 接口契约锚点

以执行卡“接口契约与技术裁决”为候选冻结增补，审批后逐字段照验，不从运行结果反推。GET `/admin/stats` 管理员200；data恰七键 `pendingCount/todayApprovedCount/userCount/workCount/statusCounts/categoryCounts/recentPending`。statusCounts恰 `pending/visible/hidden/rejected`；分类项恰 `pcid/pcname/count`；最近项恰 `pid/uid/wtitle/vurl/uname/pcname/cwtime/wstatus`，最多3条。所有计数非负JSON整数/Long；ID字符串；null键保留；T按+08:00冻结格式。

今日是服务器时区 `[00:00,次日00:00)` 内 act='pass' 的 DISTINCT pid，不按作品创建/当前展示状态统计；无缓存/新索引/DDL。全部信封恰code/msg/data，200/ok；400/invalid_request、401/unauthorized、403/forbidden、405/invalid_request、500/internal_error按对应场景，错误data=null。API不302；`/page/admin/*` 匿名/非管理员302登录，规则不改。

## GWT 用例与冻结断言（6行为组）

| 组 | Given / When / Then |
|---|---|
| TAD1-1 聚合 | 夹具含四状态各不同数量、正常/禁用账号、两个同名不同ID分类、零件分类、NULL及失联历史分类；GET stats；与独立SQL真值逐项比对，四状态和/分类和等于workCount，pending一致；零件分类保留、未分类末项固定。计数2147483648用仅限数值映射的单元替身验Long，不造数十亿行，也不以替身代替实库聚合 |
| TAD1-2 今日与最近 | 固定JVM Asia/Shanghai，构造昨日末秒/今日首秒/今日末秒/明日首秒，pass与reject、同pid两次pass、后续隐藏；今日只计区间pass的不同pid；测试计算边界跨月/年。至少5件待审含相同cwtime，最近严格cwtime DESC/pid ASC且仅3件；少于3/无待审返回真实短数组/[] |
| TAD1-3 授权 | 匿名、普通、管理员、禁用/不存在主体、过期/坏签名、坏Authorization+有效Cookie、仅有效Cookie逐例GET；401/403/200及三字段信封准确，不泄露pwd/openid/token；API无Location跳转。管理页匿名/普通302 `/login/page`，管理员200；POST stats405且无写 |
| TAD1-4 只读快照 | 先记录pwork所有计数、状态、user与waudit行；多次GET；逐项不变。确认聚合事务的一致读取，不调用会浏览+1的公开详情；空业务库得到0计数和空最近列表，分类按仍存字典返回零值 |
| TAD1-5 外壳路由 | 渲染概览而非只读模板字符串；检查fragment实参active=dash、唯一侧栏/顶栏和四个真实管理链接、index无iframe/tab拼装；旧管理页管理员访问收口新路由。AD2/3接入后的四页统一fragment由V装配验收，不在AD1阶段伪造子页完成 |
| TAD1-6 浏览器脚本 | 执行实际request与dashboard入口：只发一个stats请求、不拉分页；有数据/空/网络失败/非JSON500，提示与刷新可用；401清四项localStorage及Cookie、锁写按钮、派admin:auth-error并出现登录链接；403保留凭据、锁写按钮、提示无权限。HTTP200+数字code=200不得成功；分类零分母为0%，最近项链接编码正确；恶意标题不生成可执行节点 |

脚本测试只模拟边界设施，不把请求、统计、渲染逻辑另抄一份当被测实现；不要用字符串存在测试代替交互。真实浏览器由V在1366×768和1440×900对照确认稿：216px侧栏、四KPI、四状态/分类双栏、最近3项、暖橙八色、加载/失败反馈；无CSV、趋势/同比样例值。布局观察与自动断言分别记录。

## 真跑、红态与交回

未来在 Git Bash 仓库根执行；本轮不运行：

```bash
export JAVA_HOME=/c/develop-tools/JDK/jdk1.8.0_504
export PATH="$JAVA_HOME/bin:$PATH"
cd Backend
java -version
mvn8 -B -ntp -Dtest=AdminStatsApiTest,AdminShellTest test
node --test src/test/admin-shell.test.js
mvn8 -B -ntp -Dtest=AuditApiTest,AuthzTest,ProfileWhitelistTest,ErrorEnvelopeTest,R05SecurityTest,R05DefenseTest,IndependentR05Test test
node --test src/test/frontend-code.test.js
```

每条立即留退出码、Tests run/Failures/Errors/Skipped、末≤30行及原日志。缺类/404/目标断言失败可为目标红；编译、连接库、ParameterResolution、缺mvn8是环境/骨架失败，须修正运行方式再冻结；连续同命令环境失败3次必须换shell/绝对入口/拆步，报告原文及替代结果，不用skipTests/failIfNoTests=false。不得改POM或引依赖求绿。

交T编号→方法/参数样本映射、测试文件SHA256、红态目标缺失证据、隔离库与清理说明、未验证项。V独立复跑候选绿，在临时副本分别破坏今日边界/去重、分类过滤、权限与只读性，须触发对应断言红；不回写正式实现/冻结物。同快照全量 `mvn8 -B -ntp test` 前隔离历史产出副作用。禁止git、业务实现、改旧测试/期待值、Disabled/跳过或特判测试环境。最终手测/全量/覆盖率/CI/部署各列已验证或未验证。
