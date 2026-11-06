# AD2 审核页升级｜测试卡

状态：待终审，仅出卡。目标：冻结管理员只读详情、浏览器逐件审核及失败汇总；对应F-11/A-14/U5，6行为组。

## 输入、依赖与可写白名单

输入=全仓只读；写白名单仅以下新增文件。必读配对执行卡、需求F-11、冻结§1.1/1.4 #14–#19/2/6/七、代码风格、岗位分工、04卡对、确认稿 `docs/preview/admin_mockup.html`。依赖AD1公共shell/AdminApi、现有审核API与真实MySQL；T岗先交红态并经V审定冻结，I岗不得改测试。新增实现不作为期待值来源。

- `Backend/src/test/java/com/pg/AdminReviewApiTest.java`：MockMvc+真实MySQL、实际模板渲染，本类独立 DynamicPropertySource 库 `db_pg_test_ad2`、自包含夹具/清理。
- `Backend/src/test/admin-review.test.js`：Node内置test/vm，运行真实admin-review.js及AdminApi，以最小DOM、可控fetch/Promise和请求记录验装配，不新增库。

不得改AuditApiTest、公共基座/收集配置、POM、src/main或证据目录。输出由V收录；Java风格按冻结§七、中文注释。

## 接口契约锚点

#14待审仍原十四键、pid ASC、page默认1/size默认10上限50；不改成公开Work16键。#15无body；#16 reason非空白1–200 Unicode字符、不trim。成功200/ok的data恰pid/status，非待审或重复409/conflict；状态与单条waudit同事务。

新增GET `/admin/work/{pid}` 管理员任意状态只读：data恰work/exif/author；work恰执行卡列出的16键，无favorited/reason；exif无行null否则六键，author无关联null否则uid/nickname/avatar三键。ID字符串、计数非负JSON整数、时间+08:00；读取不增加浏览。pid非法400/invalid_request，合法不存在404/not_found；匿名401/unauthorized、普通403/forbidden、错误方法405/invalid_request、未知故障500/internal_error。信封恰code/msg/data，错误data=null；API不302，页面原302规则不变。

## GWT 用例与断言（6行为组）

| 组 | Given / When / Then |
|---|---|
| TAD2-1 详情入口 | 四状态、部分/无EXIF、历史无作者、null分类；管理员读新增详情；逐字段集合/类型/null准确，无手机号/pwd/openid；计数/状态/日志前后完全相同；合法不存在404、bad!和37位pid400，36位合法不存在404。确认 `/pending` 仍匹配原处理器和原投影 |
| TAD2-2 权限/装配 | 匿名/普通/禁用/删除主体/坏签名/过期/坏头+好Cookie/仅好Cookie调用；401/403/200对应且无API302。真渲染审核模板接AD1 fragment、active=review；执行脚本实测大图、EXIF缺失、作者占位、文本XSS与坏图。定位?pid在本页/不在本页/已处理/不存在/非法的反馈准确，非待审不能勾选提交；乱序详情返回不得覆盖新选中 |
| TAD2-3 选择与互斥 | 空/1/50件队列，全选、取消、改页/size/刷新、双击提交与跳过；空选禁用，全选只本页≤50、翻页清空，快照提交期间控件锁定；跳过零写。可控Promise阻塞第一件，断言第二件在第一件完成前未发，最大在途=1，无批量接口/Promise.all审核 |
| TAD2-4 混合结果 | 三件快照按显示顺序，fetch依次200/409/500；实际提交函数；恰三个PUT单件请求，1成功2失败0未提交，逐pid与HTTP/code展示，409不算成功。批量reject理由1/200字符合法，0/空白/201/emoji边界非法不发；合法原文逐件相同；快速二次提交不多发 |
| TAD2-5 中断与未知 | 200、401、第三件等待；另403重跑；恰前2件请求，状态succeeded/failed/unsubmitted，汇总1/1/1；401清凭据，403保留，均停止队列/锁写。普通网络失败及非JSON500逐件failed后继续、不自动重试；网络项明确结果未确认，用户刷新核实后才可重新勾选。离页后未发项不再启动 |
| TAD2-6 落库/刷新回归 | 真实API逐件对三待审approve/reject，验证每成功一条pass/null或reject/原文、uadmin为真实管理员；重复/并发与日志故障沿AuditApiTest保留。JS成功后立即移除并保留结果，刷新失败不重新提交成功项；末页最后项成功后按ceil回退，最多额外读一次；mine最近reason和公共展示过滤沿旧测试回归 |

TAD2-4/5不是仅测试测试自己写的循环，必须调用生产脚本入口并记录实际fetch、DOM结果及控件状态。数据库证据用真实API与事务，失败注入仅可作用日志插入故障，不模拟整个服务。现有AuditApiTest的严格字段断言保持不动。

V真实浏览器在1366×768、1440×900对照确认稿审核页：同一壳、340px左队列、大图contain、EXIF六项、作者、勾选批量条、理由弹窗与逐件结果；实际验证一次通过/驳回、混合失败、401/403、刷新失败/空页，不把静态截图冒充交互结果。

## 真跑、禁止行为与交回

未来Git Bash仓库根运行，本轮未运行：

```bash
export JAVA_HOME=/c/develop-tools/JDK/jdk1.8.0_504
export PATH="$JAVA_HOME/bin:$PATH"
cd Backend
java -version
mvn8 -B -ntp -Dtest=AdminReviewApiTest,AuditApiTest test
node --test src/test/admin-review.test.js src/test/admin-shell.test.js
mvn8 -B -ntp -Dtest=AuthzTest,ProfileWhitelistTest,ErrorEnvelopeTest,R05SecurityTest,R05DefenseTest,IndependentR05Test test
node --test src/test/frontend-code.test.js
```

每条立即留退出码、Tests run/Failures/Errors/Skipped、末≤30行及原日志。目标接口404/目标行为缺失可为红态；编译、缺库/JDK/mvn8、ParameterResolution不是业务红，修好执行方式再冻；同命令环境失败3次换shell/绝对入口/拆步，替代仍失败交原文与下一步。

交T编号→方法/全部样本映射、SHA256、红态与清理证据、未验证项。V独立审红/绿，在临时副本定向破坏串行await、遇401停止、逐件错误分类或详情只读，均须触发相应断言红；不回写交付代码。同快照全量 `mvn8 -B -ntp test` 前隔离历史产出副作用。禁止实现改动、git、新依赖、削弱期待值、Disabled/skipTests/failIfNoTests=false。浏览器、全量、覆盖率、CI、部署分别记录，不用自动测试替代人眼验稿。
