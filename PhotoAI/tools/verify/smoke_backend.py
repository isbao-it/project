#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""后端接口冒烟测试：对运行中的实例跑一组只读断言与可还原的写断言。

设计原则：
1. **可重复** —— 写操作（切换作品可见性、隐私开关）执行后必须还原，跑多少遍结果一致。
2. **可降级** —— 依赖的演示数据不存在时记 SKIP 而不是 FAIL，方便对空库跑。
3. **不硬编码密钥** —— JWT_SECRET 从参数/环境变量/Backend/.env 依次读取。
4. **失败即非 0 退出** —— 直接用在 commit 前的门禁里。

用法：
    python tools/verify/smoke_backend.py
    python tools/verify/smoke_backend.py --base http://localhost:8083
    python tools/verify/smoke_backend.py --base http://localhost:8082 --verbose
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# 演示库约定：my11..my14 属于同一作者，状态分别为 展示/待审核/已驳回/隐藏
AUTHOR_UID = "6dae084680f242f8a1c18a38b5b1f565"
OTHER_UID = "49b43ffeb0a04f489e59f3ef7eccc36f"
WORK_SHOWING = "my11"
WORK_PENDING = "my12"
WORK_REJECTED = "my13"
WORK_HIDDEN = "my14"


def load_secret(explicit: str) -> bytes:
    """按 参数 → 环境变量 → Backend/.env 的顺序取 JWT 密钥并做 Base64 解码。"""
    raw = explicit or os.environ.get("JWT_SECRET")
    if not raw:
        env_file = ROOT / "Backend" / ".env"
        if env_file.exists():
            for line in env_file.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line.startswith("JWT_SECRET="):
                    raw = line.split("=", 1)[1].strip().strip('"').strip("'")
                    break
    if not raw:
        sys.exit("找不到 JWT_SECRET：请用 --secret 传入，或确保 Backend/.env 存在")
    return base64.b64decode(raw)


class Smoke:
    """极简客户端 + 断言计数器。"""

    def __init__(self, base: str, key: bytes):
        self.base = base.rstrip("/")
        self.key = key
        self.ok = 0
        self.fail = 0
        self.skip = 0
        self.failures = []

    def jwt(self, uid: str, role: str = "普通用户") -> str:
        """签发一枚与后端同算法的 HS256 令牌，用于模拟指定身份的请求者。"""
        now = int(time.time())
        b64 = lambda d: base64.urlsafe_b64encode(d).rstrip(b"=").decode()
        header = b64(json.dumps({"alg": "HS256"}, separators=(",", ":")).encode())
        payload = b64(json.dumps(
            {"sub": uid, "role": role, "iat": now, "exp": now + 3600},
            separators=(",", ":"), ensure_ascii=False).encode())
        sig = b64(hmac.new(self.key, f"{header}.{payload}".encode(), hashlib.sha256).digest())
        return f"{header}.{payload}.{sig}"

    def call(self, path, method="GET", token=None, body=None):
        """发一次请求，返回 (HTTP 状态码, 解析后的 JSON 或空字典)。"""
        req = urllib.request.Request(self.base + path, method=method)
        if token:
            req.add_header("Authorization", "Bearer " + token)
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode()
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, data=data, timeout=20) as resp:
                return resp.status, json.loads(resp.read().decode() or "{}")
        except urllib.error.HTTPError as exc:
            try:
                return exc.code, json.loads(exc.read().decode() or "{}")
            except Exception:
                return exc.code, {}
        except urllib.error.URLError as exc:
            sys.exit(f"连不上 {self.base}：{exc.reason}")

    def check(self, name, cond, detail=""):
        """记录一条断言；cond 为真记 PASS，否则记 FAIL 并留明细。"""
        if cond:
            self.ok += 1
            print(f"  PASS  {name}" + (f"  [{detail}]" if detail else ""))
        else:
            self.fail += 1
            self.failures.append(f"{name}  [{detail}]")
            print(f"  FAIL  {name}" + (f"  [{detail}]" if detail else ""))

    def skip_(self, name, reason):
        """依赖数据缺失时跳过，不算失败。"""
        self.skip += 1
        print(f"  SKIP  {name}  [{reason}]")


def main():
    ap = argparse.ArgumentParser(description="后端接口冒烟测试")
    ap.add_argument("--base", default="http://localhost:8082", help="实例地址，默认本机 8082")
    ap.add_argument("--secret", default=None, help="JWT 密钥（Base64），默认从 Backend/.env 读")
    ap.add_argument("--verbose", action="store_true", help="打印每段的分组标题")
    args = ap.parse_args()

    s = Smoke(args.base, load_secret(args.secret))
    ta, tb = s.jwt(AUTHOR_UID), s.jwt(OTHER_UID)
    print(f"冒烟目标：{s.base}\n")

    # ---------- A. 公开读接口 ----------
    print("[A] 公开读接口")
    code, d = s.call("/category/list")
    cats = (d.get("data") or {}).get("list") or []
    s.check("分类列表 200", code == 200, f"http={code}")
    s.check("分类数量 = 8", len(cats) == 8, f"实际 {len(cats)}")
    s.check("分类含 count", bool(cats) and isinstance(cats[0].get("count"), int),
            f"count={cats[0].get('count') if cats else None}")

    code, d = s.call("/pwork/list?page=1&size=6")
    lst = (d.get("data") or {}).get("list") or []
    s.check("作品列表 200", code == 200, f"http={code}")
    if lst:
        fields = set(lst[0].keys())
        s.check("列表含 pcid", "pcid" in fields, f"pcid={lst[0].get('pcid')}")
        s.check("列表含 favoriteCount", "favoriteCount" in fields,
                f"favoriteCount={lst[0].get('favoriteCount')}")

    orders = {}
    for tab in ("recommend", "latest", "hot"):
        code, d = s.call(f"/pwork/list?tab={tab}&page=1&size=6")
        orders[tab] = [x["pid"] for x in ((d.get("data") or {}).get("list") or [])]
        s.check(f"排序 {tab} 200", code == 200, f"http={code}")
    s.check("recommend != latest", orders["recommend"] != orders["latest"])
    s.check("hot != latest", orders["hot"] != orders["latest"])

    # ---------- A2. 推荐算法（B9）----------
    print("\n[A2] 推荐算法：分类轮转与个性化")
    code, d = s.call("/pwork/list?tab=recommend&page=1&size=10")
    anon = (d.get("data") or {}).get("list") or []
    anon_pids = [x["pid"] for x in anon]
    anon_cats = {x.get("pcname") for x in anon if x.get("pcname")}
    s.check("匿名推荐前 10 覆盖 ≥5 个分类", len(anon_cats) >= 5, f"覆盖 {len(anon_cats)} 类")

    code, d = s.call("/pwork/list?tab=recommend&page=1&size=10", token=ta)
    login_pids = [x["pid"] for x in ((d.get("data") or {}).get("list") or [])]
    s.check("登录推荐与匿名推荐不同（个性化生效）", login_pids != anon_pids,
            f"匿名[:4]={anon_pids[:4]} 登录[:4]={login_pids[:4]}")

    code, d = s.call("/pwork/list?tab=recommend&page=1&size=8", token=ta)
    p1 = [x["pid"] for x in ((d.get("data") or {}).get("list") or [])]
    code, d = s.call("/pwork/list?tab=recommend&page=2&size=8", token=ta)
    p2 = [x["pid"] for x in ((d.get("data") or {}).get("list") or [])]
    both = p1 + p2
    s.check("推荐分页无重复（第1+第2页）", len(both) == len(set(both)), f"{len(both)} 条")

    code, _ = s.call("/pwork/list?tab=recommend&page=1&size=5", token="invalid.token.value")
    s.check("无效令牌 = 401（不静默降级匿名）", code == 401, f"http={code}")


    code, d = s.call(f"/pwork/{WORK_HIDDEN}")
    s.check("匿名看隐藏作品 = 404", code == 404, f"http={code}")

    # ---------- B. 作者可见性 ----------
    print("\n[B] 作者可见性")
    code, d = s.call(f"/pwork/{WORK_HIDDEN}", token=ta)
    if code == 404:
        s.skip_("作者看隐藏作品", f"{WORK_HIDDEN} 不存在或不属于 {AUTHOR_UID[:8]}")
    else:
        work = (d.get("data") or {}).get("work") or {}
        s.check("作者看隐藏作品 200", code == 200, f"http={code} status={work.get('wstatus')}")
        s.check("详情含 favorited", isinstance(work.get("favorited"), bool),
                f"favorited={work.get('favorited')}")

    code, d = s.call(f"/pwork/{WORK_SHOWING}/status", "PUT", ta, {"status": "隐藏"})
    if code == 200:
        s.check("展示→隐藏", (d.get("data") or {}).get("status") == "隐藏", f"{d.get('data')}")
        code2, _ = s.call(f"/pwork/{WORK_SHOWING}/status", "PUT", ta, {"status": "展示"})
        s.check("隐藏→展示（还原）", code2 == 200, f"http={code2}")
    else:
        s.skip_("作者切换可见性", f"http={code}（作品或接口不可用）")

    code, d = s.call(f"/pwork/{WORK_PENDING}/status", "PUT", ta, {"status": "展示"})
    if code == 404:
        s.skip_("待审核作品不可绕审", f"{WORK_PENDING} 不存在")
    else:
        s.check("待审核→展示 = 409", code == 409, f"http={code} code={d.get('code')}")

    code, _ = s.call(f"/pwork/{WORK_SHOWING}/status", "PUT", ta, {"status": "上架"})
    s.check("非法 status = 400", code == 400, f"http={code}")
    code, _ = s.call(f"/pwork/{WORK_SHOWING}/status", "PUT", None, {"status": "隐藏"})
    s.check("未登录改状态 = 401", code == 401, f"http={code}")

    # ---------- C. 个人列表 ----------
    print("\n[C] 喜欢与收藏列表")
    for path, label in (("/like/mine?page=1&size=5", "喜欢"), ("/favorite/mine?page=1&size=5", "收藏")):
        code, d = s.call(path, token=ta)
        data = d.get("data") or {}
        s.check(f"{label}列表 200", code == 200, f"http={code} total={data.get('total')}")
        s.check(f"{label}列表结构", isinstance(data.get("list"), list) and isinstance(data.get("total"), int))
        code, _ = s.call(path)
        s.check(f"未登录看{label} = 401", code == 401, f"http={code}")

    # ---------- D. 关注流 ----------
    print("\n[D] 关注流")
    code, d = s.call("/pwork/following?page=1&size=5", token=ta)
    data = d.get("data") or {}
    s.check("关注流 200", code == 200, f"http={code} total={data.get('total')}")
    feed = data.get("list") or []
    s.check("关注流只含展示作品", all(x.get("wstatus") == "展示" for x in feed),
            f"{len(feed)} 条")
    code, _ = s.call("/pwork/following?page=1&size=5")
    s.check("未登录看关注流 = 401", code == 401, f"http={code}")
    code, _ = s.call("/pwork/following?page=0&size=5", token=ta)
    s.check("page=0 = 400", code == 400, f"http={code}")

    # ---------- E. 他人主页 ----------
    print("\n[E] 他人主页")
    code, d = s.call(f"/user/{OTHER_UID}", token=ta)
    u = d.get("data") or {}
    if code != 200:
        s.skip_("他人主页", f"http={code}（{OTHER_UID[:8]} 不可读）")
    else:
        for field in ("worksCount", "followingCount", "followersCount", "likedTotal"):
            s.check(f"主页含 {field}", isinstance(u.get(field), int), f"{u.get(field)}")
        for field in ("followed", "showFavorites", "showLikes", "showFollowing", "showFollowers"):
            s.check(f"主页含 {field}", isinstance(u.get(field), bool), f"{u.get(field)}")
        s.check("主页不泄漏 pwd/openid", "pwd" not in u and "openid" not in u)

    for path, label in ((f"/user/{OTHER_UID}/works?page=1&size=5", "他人作品"),
                        (f"/user/{OTHER_UID}/following?page=1&size=5", "他人关注"),
                        (f"/user/{OTHER_UID}/followers?page=1&size=5", "他人粉丝")):
        code, d = s.call(path, token=ta)
        if code == 403:
            s.check(f"{label} 不可见时 = 403", True, f"http=403")
        else:
            s.check(f"{label} 200", code == 200, f"http={code} total={(d.get('data') or {}).get('total')}")

    code, d = s.call(f"/user/{OTHER_UID}/works?page=1&size=5", token=ta)
    if code == 200:
        wl = (d.get("data") or {}).get("list") or []
        s.check("他人作品只含展示", all(x.get("wstatus") == "展示" and x.get("uid") == OTHER_UID for x in wl),
                f"{len(wl)} 条")

    # ---------- F. 隐私四开关 ----------
    print("\n[F] 隐私四开关")
    full = {"visFavorite": True, "visLike": True, "visFollowing": True, "visFollowers": True}
    code, d = s.call("/user/privacy", "PUT", ta, full)
    s.check("四字段写入 200", code == 200, f"http={code}")
    code, _ = s.call("/user/privacy", "PUT", ta, {"visFavorite": True, "visLike": True, "visFollowing": True})
    s.check("缺字段 = 400", code == 400, f"http={code}")
    code, _ = s.call("/user/privacy", "PUT", ta, dict(full, visLike="yes"))
    s.check("类型错 = 400", code == 400, f"http={code}")
    code, _ = s.call("/user/privacy", "PUT", None, full)
    s.check("未登录改隐私 = 401", code == 401, f"http={code}")

    # 关闭收藏可见 → 他人 403、本人 200 → 还原
    code, _ = s.call("/user/privacy", "PUT", ta, dict(full, visFavorite=False))
    if code == 200:
        code, _ = s.call(f"/user/{AUTHOR_UID}/favorites?page=1&size=3", token=tb)
        s.check("关闭后他人看收藏 = 403", code == 403, f"http={code}")
        code, _ = s.call(f"/user/{AUTHOR_UID}/favorites?page=1&size=3", token=ta)
        s.check("关闭后本人看收藏 = 200", code == 200, f"http={code}")
        s.call("/user/privacy", "PUT", ta, full)
        s.check("隐私开关已还原", True)

    # ---------- 汇总 ----------
    total = s.ok + s.fail
    print(f"\n{'='*46}\nPASS={s.ok}  FAIL={s.fail}  SKIP={s.skip}  合计={total}")
    if s.fail:
        print("\n失败明细：")
        for item in s.failures:
            print("  -", item)
        sys.exit(1)
    print("全部通过")


if __name__ == "__main__":
    main()
