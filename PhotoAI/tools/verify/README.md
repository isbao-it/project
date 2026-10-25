# 后端接口冒烟测试

`smoke_backend.py` 对**运行中的后端实例**跑一组接口断言，覆盖本轮新增的可见性、关注流、他人主页、
隐私四开关等能力。目的是把「我记得验过」变成「随时能重验」——改动前后各跑一次，就知道有没有碰坏别的东西。

## 用法

```bash
# 对默认的本机 8082 跑
python tools/verify/smoke_backend.py

# 对临时实例跑（推荐：先在 8083 起一个新实例，验完关掉，不打扰日常实例）
python tools/verify/smoke_backend.py --base http://localhost:8083

# JWT 密钥默认从 Backend/.env 的 JWT_SECRET 读，也可显式传
python tools/verify/smoke_backend.py --base http://localhost:8083 --secret "<base64-secret>"
```

- **退出码 0 = 全过**，非 0 = 有 FAIL，失败明细会列在末尾，可直接当 commit 前的门禁。
- 依赖数据缺失时记 `SKIP` 而不是 `FAIL`，所以对空库也能跑（只是覆盖变少）。

## 断言分组

| 组 | 覆盖 |
|---|---|
| A 公开读 | 分类列表（8 个、含 count）、作品列表（含 `pcid` / `favoriteCount`）、三种排序互不相同、匿名看隐藏作品 404 |
| B 作者可见性 | 作者能读自己的非展示作品、详情含 `favorited`、展示↔隐藏往返、待审核强改展示 409、非法 status 400、未登录 401 |
| C 个人列表 | `/like/mine`、`/favorite/mine` 结构与未登录 401 |
| D 关注流 | `/pwork/following` 只含展示作品、未登录 401、`page=0` 400 |
| E 他人主页 | `worksCount/followingCount/followersCount/likedTotal` 为数字，`followed/show*` 为布尔，不泄漏 `pwd`/`openid`；他人作品/关注/粉丝三列表 |
| F 隐私四开关 | 四字段写入、缺字段 400、类型错 400、未登录 401；关闭收藏可见后他人 403 / 本人 200，**验完自动还原** |

## 设计约束（改脚本时守住）

1. **可重复**：任何写操作（切换作品状态、改隐私开关）执行后必须还原，跑多少遍结果一致。
2. **不硬编码密钥**：JWT 从参数 / 环境变量 / `Backend/.env` 依次读取，密钥不进仓库。
3. **不假设唯一数据**：断言只看「是不是数字/布尔」「是不是展示作品」，不硬编码某个 uid 的作品条数。
4. **依赖数据缺失就 SKIP**：脚本里的 `AUTHOR_UID` / `OTHER_UID` / `my11`~`my14` 是演示库约定，
   换库后如果这些不存在，相关断言自动跳过而不是报错。
5. **只走 HTTP**：不直连数据库，保证对任何部署方式都能跑。

## 加新断言

在对应分组里加一行 `s.check("断言名", 条件, "明细")` 即可。新写接口时同步加进来，
这样下次改动就有东西替它守着。

## 已知未覆盖

- **小程序前端**（视觉、交互、跳转）不在本脚本范围内——那部分靠人眼验收。
- 后台管理页（`/page/admin/*`）、上传接口（`/upload/work`）不在内，需要时另行补。
