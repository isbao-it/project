# AD3 作品与用户管理｜执行卡

状态：待终审，仅出卡，未派工。范围：C / M3；F-11/A-14、U5/U7/U8。

## 目标、输入与依赖

实现四状态作品管理、冻结用户API接线及三项按钮保护。输入=全仓只读，白名单只约束写入。必读需求F-11、冻结§1.1/1.4 #14–#19/2/6 U5 U7 U8/七、代码风格、岗位分工、04卡对及 `docs/preview/admin_mockup.html`。冻结§七优先：构造器注入、显式Map、BizException、Long、中文Javadoc、UTF-8/CRLF，无新依赖。

依赖AD1外壳/AdminApi/路由、AD2管理员详情及审核定位；顺序AD1→AD2→AD3。T先冻结测试，I只读，V独立复跑。index/PageController/公共壳归AD1，审核模板/详情归AD2。

## 可写白名单（逐文件，既有文件仅增量）

| 文件 | 允许范围 |
|---|---|
| `Backend/src/main/resources/templates/pwork/pworkList.html` | 独占重做作品管理内容，接公共shell |
| `Backend/src/main/resources/templates/user/userList.html` | 独占重做用户管理内容，接公共shell，移除旧增改角色/批删入口 |
| `Backend/src/main/resources/static/admin/admin-works.js` | 新增筛选/分页、状态/删除、只读详情调用 |
| `Backend/src/main/resources/static/admin/admin-users.js` | 新增用户分页/启禁/删除/保护信息装配 |
| `Backend/src/main/resources/static/admin/admin-manage.css` | 新增，限定 `#admin-works/#admin-users` 根节点 |
| `Backend/src/main/java/com/pg/controller/AdminWorkController.java` | 仅增加list/status方法及新服务构造器依赖，不动原pending/approve/reject签名/行为 |
| `Backend/src/main/java/com/pg/service/AdminWorkService.java` | 新增 `Map<String,Object> list(String status,String keyword,String category,int page,int size)`、`Map<String,Object> changeStatus(String pid,Object status,String adminUid)` |
| `Backend/src/main/java/com/pg/service/impl/AdminWorkServiceImpl.java` | 新增查询/管理员状态事务；不调用发布流程 |
| `Backend/src/main/java/com/pg/mapper/AdminWorkMapper.java` | 新增只读列表/count |
| `Backend/src/main/resources/mapper/AdminWorkMapper.xml` | 新增同过滤list/count、最近reason投影 |
| `Backend/src/main/java/com/pg/mapper/AuditMapper.java` | 只新增管理员状态行锁读取、非待审条件更新方法，不改既有方法 |
| `Backend/src/main/resources/mapper/AuditMapper.xml` | 只新增上述两个statement，不改pending/updatePending/insertAudit等既有SQL |
| `Backend/src/main/java/com/pg/controller/AdminUserController.java` | 仅增加GET guards，不动#17–#19 |
| `Backend/src/main/java/com/pg/service/AdminUserService.java` | 仅增 `Map<String,Object> guards(String uids,String adminUid)` |
| `Backend/src/main/java/com/pg/service/impl/AdminUserServiceImpl.java` | 仅增只读guards投影/校验；不改list八键及changeStatus/delete的保护事务 |
| `Backend/src/main/java/com/pg/mapper/AdminUserMapper.java` | 仅增guards只读查询方法 |
| `Backend/src/main/resources/mapper/AdminUserMapper.xml` | 仅增guards用户/全状态作品/正常管理员计数SQL |
| `docs/接口增补_AD3.md` | 新增，终审后记录下述三项接口增补及状态矩阵 |

所有其它文件只读，尤其PworkController/Service/Mapper、AuditService/Impl、AuthInterceptor、旧view/workDetailList.html、已有冻结文档及所有测试。无DDL/迁移授权。

## 契约与高风险裁决（本节批准后冻结增补）

共用冻结§2：顶层恰code/msg/data；200/ok；错误data=null，400/invalid_request、401/unauthorized、403/forbidden、404/not_found、409/conflict、405/invalid_request、500/internal_error。新接口全部类/方法管理员注解，沿现有鉴权查主体状态与JWT claim角色；API返回JSON、不302；`/page/admin/*`页面原302规则保持。AdminApi统一处理401/403，禁止子页重新实现。

### 1. 管理员作品查询

新增 **GET `/admin/work/list`**，不是公开 `/pwork/list`，也不改变#14固定待审。

| Q参数 | 精确语义 |
|---|---|
| `status:string` | 省略=全部；存在时仅待审核/展示/隐藏/已驳回四值，空串/空白/带空格/其它值一律400，不trim、不降级为全部 |
| `keyword:string` | 选填，≤50 Unicode字符；省略/空串无筛选，其余不trim；wtitle或user.uname字面子串匹配，或pid精确匹配；LIKE的%/_/转义符须转为字面，SQL参数绑定，不拼SQL |
| `category:string` | 选填，对应pcid，省略无筛选；传入必须 `[A-Za-z0-9_-]{1,36}`，空串/非法400，格式合法但不存在200空页；不新增“未分类”伪ID |
| `page:int`、`size:int` | 默认1/10，page≥1、size1–50，非整数/越界400；超页200空list及真实total。传pageNum/pageSize任一旧别名400，不作别名 |

data恰 `{list:AdminWork[],total:N}`；过滤条件AND组合，先过滤后分页，list/count同条件；按 `cwtime DESC,pid ASC` 稳定排序，无sort参数。AdminWork恰17键：`pid,uid,wtitle,wdesc,vurl,viewcount,votecount,wstatus,cwtime,likep,uname,avatar,pcid,pcname,realLikeCount,favoriteCount,reason`。前16键逐字段类型/null遵当前冻结Work，无favorited；reason额外string|null，按waudit.ct DESC/aid DESC最近一条，最近reject取原文，最近pass或无日志null。读查询不写状态、不增浏览。N是非负JSON整数，Java Long；ID字符串；T按冻结+08:00格式。

### 2. 管理员非待审状态修改

**裁决：不能复用 `PUT /pwork/{pid}/status`。** 冻结#9b及PworkServiceImpl.updateVisibility同时限定uid=作者，只允许本人展示/隐藏，管理员对他人也404。不能放宽作者接口。

新增 **PUT `/admin/work/{pid}/status`**，pid按36位ID规则；JSON body必须对象，`status:string`必填、仅展示/隐藏，无trim/类型转换；缺失/null/待审核/已驳回/其它类型400。成功data恰 `{pid:ID,status:string}`；合法不存在404。

| 当前 → 目标 | 结果与日志 |
|---|---|
| 展示→隐藏、隐藏→展示 | 200更新，仅可见性变化，不追加审核日志 |
| 展示→展示、隐藏→隐藏 | 200幂等，无写入/日志 |
| 已驳回→展示 | 200；同时追加唯一waudit `act=pass,reason=NULL,uadmin=已验uid,ct=现有审核时间口径`，清除mine的旧驳回理由语义；AD1今日通过按pid去重包含此记录 |
| 已驳回→隐藏 | 409，不隐式恢复或藏起审核结论 |
| 待审核→展示/隐藏 | 409，必须跳审核页走#15/#16，不绕审核 |

状态写入只由AuditMapper新增方法承担；service同事务先行锁读再条件更新，不在其它Mapper写状态。恢复日志失败则回滚状态、保留历史；并发恢复仅1条pass，后到请求按展示幂等200。新pass必须在既有ct DESC/aid DESC下成为最近记录，同秒旧reject也不能残留为mine理由。既有#15/#16重复仍409。只改wstatus，禁止改内容/计数或调用发布/编辑/安全审核/EXIF/向量流程，不新增状态/act/角色。

### 3. 用户列表与按钮保护

**不加workCount、不显示作品数。** `AuditApiTest` 的ADMIN_USER_KEYS、aud_13、assertAdminUser→assertKeys严格断言八键，禁止改弱。#17保持 `uid/nickname/avatar/bio/role/status/pnum/createdAt`、list/total；keyword≤50、page/size默认1/10、size≤50。#18 PUT `/admin/user/{uid}/status` JSON status仅正常/禁用；#19 DELETE `/admin/user/{uid}` 无body、成功data=null。保留原实现。

仅八键不足以知晓全状态作品和全库最后正常管理员；**新增独立只读 GET `/admin/user/guards?uids=<逗号分隔ID>`**，不向AdminUser塞字段、不拉所有分页推算。uids必填，1–50个不重复uid，每项 `[A-Za-z0-9_-]{1,64}`；空项/重复/超量/非法400，任一合法ID不存在404，整次失败data=null。data恰 `{list:[...]}`，顺序同请求；每项恰 `uid:ID,canDisable:boolean,canDelete:boolean,disableReason:string|null,deleteReason:string|null`，不返回workCount。

同一只读快照按真实数据库计算：自己时两权限false、原因self；否则目标数据库role=管理员且status=正常且全库正常管理员数=1，两权限false、原因last_admin；否则canDisable=true/disableReason=null，目标有任意四状态作品时canDelete=false/deleteReason=has_works，否则true/null。优先级self>last_admin>has_works。它只描述这三项保护，不保证其它RESTRICT关系可删；不是写授权凭证。无缓存/新索引，按当前页最多50个uid一批查，不逐行N+1。

每次列表加载后请求本页guards；未成功前禁用禁用/删除按钮并提示加载中/失败，旧响应不得覆盖新页。自己/最后管理员两按钮置灰并解释，有作品仅删除置灰，不禁启用；自己正常按钮省略。写后重读列表/guards，过期信息触发403/409时提示刷新，不乐观删行。U7兜底：自禁/自删403优先；最后正常管理员409、有任意状态作品删除409。保留共同管理员集合锁及并发保护，禁止级联作品。

## 页面范围

调用公共shell，active=works/users，根 `#admin-works/#admin-users`；按确认稿暖橙八色/表格/标签/筛选分页/提示层级，1366×768与1440×900验稿。作品筛选status/keyword/category，查询/重置回第1页、size默认10；`/category/list`仅供字典，其公开计数不作全状态数。全部/四状态数只读AD1 stats，禁止分页聚合。

作品行：待审核仅“去审核”跳AD2定位；展示“隐藏”、隐藏“恢复展示”、已驳回“重新通过”（走新status），状态写需确认且单次锁按钮。详情读AD2新接口不增浏览；删除复用既有#10 `DELETE /pwork/{pid}`（作者或管理员），确认后调用，不改原删除事务；待审页不批量删除。新页面不保留独立“作品视图”导航。

用户页只接#17/#18/#19和guards；关键词筛选昵称，移除确认稿中的角色/状态筛选（冻结#17不支持，禁止只筛当前页伪造全量）、作品数列及旧新增/编辑角色/批删入口。允许管理员完整手机号按冻结显示，无密码/openid。所有外来文本用安全文本节点，图片限定http(s)/同源相对地址，空/加载/错误与分页明确；不引CSV导出或新排序接口。

## GWT 验收（7行为组）

| 组 | Given / When / Then |
|---|---|
| AD3-G1 查询 | 四状态、同名分类、标题/作者/ID关键词；组合查询；精确路径/参数/17键、过滤total、稳定分页及最近reason正确；非法status400而非全部，旧别名400、合法不存在分类空页 |
| AD3-G2 状态 | 非本人作品覆盖矩阵全部8格；管理员请求；正确200/409及pass次数、reason归零；作者旧入口他人仍404，非法目标400、合法不存在404 |
| AD3-G3 原子/权限 | 并发恢复已驳回、日志插入故障、匿名/普通/坏凭据/禁用主体；分别一条pass/回滚/401或403；原审核重复409不改，无内容/计数副作用 |
| AD3-G4 用户保护 | 自己、最后正常管理员、四状态有作品用户、可删普通用户；列表/guards与按钮；八键不加workCount，按优先级置灰、允许启用；不存在/非法uids准确拒绝 |
| AD3-G5 兜底 | guards成功后另会话使信息过期，再直接写接口/绕按钮；自操作403，最后管理员/有作品删除409，原样保留；两管理员并发互减不归零，禁止级联 |
| AD3-G6 页面 | 管理员直达/刷新两页；同壳/正确激活项/暖橙表格，筛选重置分页接真实API；待审跳AD2，详情零浏览，删除走#10；移除未支持控件与作品数列 |
| AD3-G7 异常 | guards慢/失败/旧响应、状态写失败、401/403/409、末页删最后项；保护信息未就绪禁写，异常清晰、无重复提交，成功后重读/回退有效页；API JSON与页面302不变 |

## 禁止行为、真跑与交回

禁止改已有严格字段断言、Pwork作者权限、#14–#19语义、最后管理员锁、旧Work公开投影；不新增批量API/角色/缓存/媒体审核/CSV；不改依赖、不执行git，不动他卡文件，I不写测试。

未来Git Bash仓库根运行，JDK/Maven/隔离库先核验；本轮未运行：

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

每条立即记录退出码与测试数/失败/错误/跳过、日志末≤30行和原日志；环境失败同命令3次必须换绝对入口/shell/拆步，附原文/替代结果，不skipTests。I交增量文件范围、增补契约、绿证据/状态矩阵与未验证项。V同快照复跑、隔离历史输出后全量 `mvn8 -B -ntp test`，实浏览器四页统一壳与三项保护验收；CI/部署/覆盖率另记，不以全量绿冒充。
