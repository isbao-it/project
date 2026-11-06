# AD2 审核页升级｜执行卡

状态：待终审，仅出卡，未派工。范围：C / M2；对应 F-11、A-14、U5。

## 目标、输入与依赖

待审队列支持当前页勾选、逐件通过/驳回与结果汇总；右侧大图、EXIF六项和作者资料按确认稿还原。输入=全仓只读，写入仅限下表。必读需求F-11、冻结§1.1/1.4 #14–#19/2/6 U5 U7 U8/七、代码风格、岗位分工、04卡对及视觉稿 `docs/preview/admin_mockup.html`。冲突按冻结§七；Boot2.4.5/JDK8、构造器注入、显式Map、BizException、中文Javadoc、UTF-8/CRLF，不引依赖。

依赖 AD1 外壳及 AdminApi 已交付；既有 #14/#15/#16 和 waudit 事务作为不改的基线。先独立T岗冻结测试，I岗只读，V岗复跑。AD3读取本卡新增管理员详情，不修改本卡文件。

## 可写白名单（逐文件）

| 文件 | 允许范围 |
|---|---|
| `Backend/src/main/resources/templates/admin/audit.html` | 独占重写审核内容，接公共shell，不复制壳 |
| `Backend/src/main/resources/static/admin/admin-review.js` | 新增队列选择、大图详情、逐件请求与结果状态机 |
| `Backend/src/main/resources/static/admin/admin-review.css` | 新增样式，限定 `#admin-review` 内 |
| `Backend/src/main/java/com/pg/controller/AdminReviewController.java` | 新增只读管理员详情，类级RequiresRole管理员 |
| `Backend/src/main/java/com/pg/service/AdminReviewService.java` | 新增 `Map<String,Object> detail(String pid)` |
| `Backend/src/main/java/com/pg/service/impl/AdminReviewServiceImpl.java` | 新增只读详情、输入校验和投影 |
| `Backend/src/main/java/com/pg/mapper/AdminReviewMapper.java` | 新增只读详情接口 |
| `Backend/src/main/resources/mapper/AdminReviewMapper.xml` | 新增显式字段查询，不增加浏览 |
| `docs/接口增补_AD2.md` | 新增，终审后记录本卡只读详情契约 |

不写 index/layout/PageController/admin-api；不写 AdminWorkController、AuditService/Impl、AuditMapper、PworkController/Service、WorkQueryService/Mapper。既有审核写接口无需变化；若基线缺陷阻断，给复现交原责任卡，不越界修复。

## 接口契约（新增部分终审后冻结）

沿用冻结 #14 `GET /admin/work/pending?page=1&size=10`，固定仅待审核、原十四键投影及pid ASC排序保持，不偷偷升级字段；分页page≥1、size=1–50、非整数/越界400、超页200空list与真实total。#15 `PUT /admin/work/{pid}/approve` 无body；#16 `PUT /admin/work/{pid}/reject` JSON `{reason:string}`，非全空白、1–200 Unicode字符，检查但不trim原文。成功200/ok，data恰 `{pid,status}`（展示/已驳回）；非法输入400/invalid_request、不存在404/not_found、非待审核/重复409/conflict。每次成功由既有审核事务写状态及唯一waudit；pass/null或reject/原文，uadmin来自已验身份；日志失败整笔回滚。**不新增批量接口。**

新增 `GET /admin/work/{pid}`：仅管理员，可查任意四状态；pid严格 `[A-Za-z0-9_-]{1,36}`，格式400，合法不存在404。200/ok，data恰 `work/exif/author`：

- work恰16键 `pid,uid,wtitle,wdesc,vurl,viewcount,votecount,wstatus,cwtime,likep,uname,avatar,pcid,pcname,realLikeCount,favoriteCount`；类型/null语义按当前冻结§1.1 Work，不带favorited/reason。计数Java Long、JSON非负整数，ID字符串。
- exif为null（无行）或恰 `focal/aperture/shutter/iso/lens/camera` 六键，缺值null。
- author为null（无作者）或恰 `uid/nickname/avatar`，无手机号、角色、积分、获赞/作品总数；不为视觉稿示例另拉资料聚合。

详情为只读一致性查询，不使用公开 `/pwork/{pid}`：管理员查他人待审在旧接口会404，且公开详情会浏览+1。新详情任何状态不增加浏览/点赞、不触发EXIF解析或发布流程。静态 `/pending` 仍命中旧处理器，不能被动态pid吞掉。

全部API顶层恰code/msg/data，错误data=null；401/unauthorized、403/forbidden、405/invalid_request、500/internal_error按冻结§2；API仍JSON，无302。沿既有鉴权，每次查主体状态、JWT claim角色、坏头不回退Cookie。页面302规则保持，API异常统一调用AD1 AdminApi，不另写鉴权。

## 页面与批量算法冻结

`admin/layout :: shell(title,active,content,scripts)`，active=review，根 `#admin-review`；右侧大图contain、左队列340px、图片区340px高，暖橙八色及间距/层级以确认稿为准。通过真实AdminApi请求和textContent渲染；图片仅同源相对或http(s)，坏图/无EXIF/无作者显示占位。快捷参数 `?pid=` 仅定位待审：先用详情核验状态再显示选中；不在当前页可作独立选中详情，勾选仍仅当前页；不存在/已处理提示刷新，不把非待审加入操作队列。忽略非法pid前端定位并提示，无写请求。

1. 勾选集只含当前已加载页的待审pid；全选只选本页，最多50，翻页/改size/刷新清空，不跨页隐性全选。空选批量按钮禁用；跳过只切详情，不发写请求。
2. 提交前确认件数；批量驳回复用一次输入的合法理由原文，各件单独写日志。冻结本次勾选快照，按队列显示顺序串行await #15或#16，最大同时在途写请求=1；提交期间禁勾选、分页、刷新和二次提交。
3. 每件状态恰 `waiting/running/succeeded/failed/unsubmitted`；初始waiting，发出running，再进入succeeded或failed；停止后未发项unsubmitted。显示pid/标题、结果与失败HTTP/code，成功数/失败数/未提交数之和=快照件数。400/404/409/500及网络失败记该件failed后继续下一件；不把409当成功，不自动重试。网络失败文案“结果未确认，请刷新核实”，不得谎称未落库。
4. 401/403到达即经共享事件/异常停止后续发出，当前件failed，剩余unsubmitted，保留已经完成的结果；401清凭据并登录提示，403保留凭据并无权限提示。成功项立即移出可审核列表，刷新失败不能让成功项再提交。
5. 批次结束只刷新一次当前队列；末页为空退回 `max(1,ceil(total/size))` 后再读一次。刷新失败和审核结果分开提示，结果面板不丢；只允许用户刷新核实后重新勾选失败项，不自动回放整批。详情快速切换用请求序号/取消防旧响应覆盖新选项；退出页面不启动未发请求，已发请求可能落库须如实提示。

## GWT 验收（6行为组）

| 组 | Given / When / Then |
|---|---|
| AD2-G1 详情 | 四状态及缺EXIF/缺作者作品；管理员读新增详情；精确字段/null、无浏览变化；匿名401、普通403；非法pid400/不存在404 |
| AD2-G2 布局 | 1366×768/1440×900；直达审核与概览跳转；公共侧栏顶栏、左队列右大图/EXIF/作者正确，选中状态/坏图/空页明确，恶意文本不执行 |
| AD2-G3 选择 | 当前页0/1/50条勾选/全选/翻页；批量仅对应快照，空选禁用，翻页清空、跳过零写；反复点击仅一次队列 |
| AD2-G4 逐件 | 三件分别200/409/500；提交批量；三个单件请求串行、成功/失败逐项显示，成功1失败2，成功项仅1条日志；统一驳回理由逐件原文入库，事务由04保障 |
| AD2-G5 中断 | 第一件成功、第二件401或403、第三件未发；提交；第二件failed第三件unsubmitted，汇总1/1/1，停止写请求，鉴权提示分流；网络未知结果不自动重试 |
| AD2-G6 回归 | 重复/并发审核、日志故障、末页最后一件/刷新失败、详情响应乱序；04行为仍409/原子回滚，成功反馈不变失败，旧详情不覆盖新选中；公共可见性及mine最近reason不退化 |

## 禁止行为、真跑与交回

不得改冻结#14–#19、批量服务端事务/路由、审核SQL、公开详情可见性或浏览计数；不得批量Promise.all并发、自动重试、跨页批量、伪造整批成功。无CSV、媒体自动审核、缓存或新角色；无iframe、layer type=2、新依赖、git操作，I岗不写测试。

未来在Git Bash仓库根运行，MySQL仅隔离库；本轮未运行：

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

每条立即记录退出码、测试数/失败/错误/跳过、日志末≤30行与原日志。mvn8/JDK核验失败先查现有环境、换绝对Maven入口/shell/拆步；同命令环境失败3次必须换方式，不skipTests掩盖。I交白名单文件、契约增补、绿证据、批次逐件结果与未验证项；V复跑同快照全量 `mvn8 -B -ntp test`（先隔离历史输出副作用），实浏览器走通过/驳回、混合失败、401/403、末页回退与公共可见性。测试绿/浏览器/覆盖率/CI/部署分开报告。
