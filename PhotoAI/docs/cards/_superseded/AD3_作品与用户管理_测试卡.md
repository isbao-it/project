# AD3 作品与用户管理｜测试卡

状态：待终审，仅出卡。目标：独立冻结作品查询/状态矩阵、用户八键兼容与按钮保护；F-11/A-14、U5/U7/U8，7行为组。

## 输入、依赖与可写白名单

输入=全仓只读；必读配对执行卡、需求F-11、冻结§1.1/1.4 #14–#19/2/6/七、代码风格、岗位分工、04卡对及确认稿 `docs/preview/admin_mockup.html`。依赖AD1外壳、AD2详情/定位、既有审核与用户事务。T先产红态冻结，I只读，V独立审定；期待值来自卡面契约，不能从新增实现倒推。

仅允许新增：

- `Backend/src/test/java/com/pg/AdminManageApiTest.java`：MockMvc+真实MySQL，本类独立 DynamicPropertySource 库 `db_pg_test_ad3`、自包含夹具、独立连接竞争与清理；模板用真实Thymeleaf渲染。
- `Backend/src/test/admin-manage.test.js`：Node内置test/vm，运行真实admin-works.js/admin-users.js和共享AdminApi；可控fetch/DOM及乱序Promise验状态与请求。

不得修改AuditApiTest/AdminStatsApiTest/AdminReviewApiTest、共享基座/夹具/配置、src/main、POM、证据目录。测试代码中文注释，Java按冻结§七。红态发现骨架缺陷只修自己新增文件，环境故障换执行方式。

## 接口契约锚点

执行卡已裁决：作者PUT `/pwork/{pid}/status`不允许管理员操作他人，保留404；新增GET `/admin/work/list`及PUT `/admin/work/{pid}/status`。查询status只允许四枚举，省略全部、空/非法400不降级；keyword≤50字符、字面标题/作者子串或精确pid；category=pcid合法不存在空页；page/size默认1/10、size≤50、旧别名400。响应list/total，AdminWork精确17键及最近reason，排序cwtime DESC/pid ASC，读取零写。

状态body status只展示/隐藏；展示↔隐藏及同值200无日志；已驳回→展示200追加pass/null同事务；已驳回→隐藏409；待审核两个目标均409。现有#15/#16重复仍409。匿名401/unauthorized、普通403/forbidden、非法400/invalid_request、不存在404/not_found、冲突409/conflict、错误方法405/invalid_request、未知故障500/internal_error；信封恰code/msg/data，错误data=null。

#17 AdminUser恰八键，禁止加workCount：AuditApiTest现有严格集合断言必须原样绿。新增GET `/admin/user/guards?uids=...`，1–50不同合法uid，非法400、任一不存在404；data仅list，各项仅uid/canDisable/canDelete/disableReason/deleteReason。原因优先级self>last_admin>has_works；可操作原因null，canDisable不受has_works影响。#18/#19原三项后端保护不变：自禁自删403，最后正常管理员与有作品删除409；不能统一为409。

## GWT 断言（7行为组）

| 组 | Given / When / Then |
|---|---|
| TAD3-1 查询 | 四状态、相同cwtime、同名不同ID分类、null分类、同作者，至少11条匹配；省略/各status、keyword标题/作者/精确pid、中文/emoji50与51、字面%/_/反斜杠、无命中、category组合；逐项精确list与SQL独立真值total，默认10、size1/50、超页空list。page0/-1/小数/溢出、size0/51/文本、旧别名、status空/空格/带空格/其它400；category非法400/合法不存在空页。17键/null/Long与reason最近ct/aid降序、最新pass/null正确，列表不增加浏览 |
| TAD3-2 状态矩阵 | 非本人作品，展示/隐藏×两目标、已驳回×两目标、待审核×两目标，共8格；按执行卡200/409与日志逐值验证，已驳回恢复后mine.reason=null且历史reject保留，含同秒旧reject的ct/aid排序边界；重复恢复不加日志；旧作者入口管理员他人仍404。目标缺失/null/空/带空格/待审核/已驳回/数字/数组400，pid非法400、合法不存在404 |
| TAD3-3 原子/权限 | 真实并发两连接恢复同一已驳回，两次200但只1条pass；日志插入后端故障500且状态/日志回滚；不替代整服务伪装事务。对三个新接口逐测匿名/普通/过期/坏签名/禁用或删除主体/坏头好Cookie/仅好Cookie；401/403/200正确、无API302、无敏感字段。确认内容/EXIF/embedding/计数不变、#15/#16重复409原样，错误方法405零写 |
| TAD3-4 保护元数据 | 自己、仅一个正常管理员、禁用管理员、四状态各有作品、零作品普通用户；调用guards，按请求顺序精确五键、self优先last_admin优先has_works、布尔与原因逐项一致、零写；允许禁有作品普通用户但禁删。uids缺失/空/空项/重复/51项/非法/65位400，1/50项合法，64位合法不存在404，任一不存在整次404。#17八键/完整管理员手机号及null仍不变，无workCount列 |
| TAD3-5 服务端兜底 | guards后另会话新增作品或改变管理员数量，再直接DELETE/status绕按钮；409且目标/作品保留；自禁自删403含最后一人，自己设正常200，有作品用户启用200；并发两管理员禁/删共同保护仍至少1正常管理员。沿AuditApiTest原锁/防删断言不删不弱化，不靠元数据授权 |
| TAD3-6 页面与安全 | 真渲染两个模板接统一shell、active=works/users；执行实际脚本验证查询/重置回page1、正确参数、待审仅跳AD2定位、详情读新接口、删除只调既有#10、状态按钮确认互斥；文本XSS不执行。用户仅#17/#18/#19+guards，去作品数/角色状态筛选/旧新增编辑与批删，不用本页筛选冒充全库 |
| TAD3-7 异常/竞态 | guards未回/失败时禁用禁用与删除按钮，信息加载完仅正确解除；翻页乱序旧guards不覆盖新页；自己/最后管理员/有作品对应置灰原因，启用不误禁。写返回403/409后提示+重读而非乐观移除，成功后列表+guards刷新；末页删最后项回退有效页；401清凭据、403保留，均锁写；网络/非JSON失败可见且不自动重试 |

V在1366×768、1440×900实浏览器对照确认稿：同壳、暖橙八色、作品四状态表、筛选/分页、用户保护提示/置灰、空/失败态、键盘可操作及确认。移除稿中超范围控件是已裁决差异，不判视觉缺陷；不得用截图/源码字符串声称交互通过。

## 真跑、交回与禁止行为

未来Git Bash仓库根执行，本轮未运行：

```bash
export JAVA_HOME=/c/develop-tools/JDK/jdk1.8.0_504
export PATH="$JAVA_HOME/bin:$PATH"
cd Backend
java -version
mvn8 -B -ntp -Dtest=AdminManageApiTest,AuditApiTest,AdminStatsApiTest,AdminReviewApiTest test
node --test src/test/admin-manage.test.js src/test/admin-shell.test.js src/test/admin-review.test.js
mvn8 -B -ntp -Dtest=AuthzTest,ProfileWhitelistTest,ErrorEnvelopeTest,R05SecurityTest,R05DefenseTest,IndependentR05Test test
node --test src/test/frontend-code.test.js
```

每条立即保存退出码、Tests run/Failures/Errors/Skipped、末≤30行与原日志；缺mvn8/JDK/库或编译失败先修运行/骨架，不当目标红。同命令环境失败3次换shell/绝对Maven入口/拆步，替代仍失败交原文、各结果与下一步。禁止skipTests、failIfNoTests=false、Disabled、改旧测试期待值、新依赖、实现改动及git。

交全部T→方法/参数样本映射、SHA256、红态及隔离库清理/并发证据、未验证项。V独立复跑候选绿；在临时副本定向放宽非法status、去掉状态/日志事务、给AdminUser多一键、移除最后管理员保护、让旧guards覆盖新页，均应使对应断言红；不回写正式实现/冻结物。隔离历史输出副作用后同快照全量 `mvn8 -B -ntp test`，三卡四页最终装配手测；自动/手测/覆盖率/CI/部署分别注明已验证或未验证。
