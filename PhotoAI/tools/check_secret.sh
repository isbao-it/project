#!/bin/bash
# 提交前敏感信息扫描
#
# 用法：
#   bash tools/check_secret.sh            # 扫工作区（默认）
#   bash tools/check_secret.sh --staged   # 只扫已暂存的内容（提交前推荐）
#
# 退出码：0 = 未发现可疑项；1 = 有可疑项，需人工确认后再提交。
#
# 背景：本仓库可能被推到公开远端，曾出现过真实手机号、生产备份 SQL、
# 数据库导出、含 JWT 的调试片段进入工作区的情况，故固化为脚本。

set -uo pipefail

MODE=${1:-worktree}
cd "$(dirname "$0")/.." || exit 1

if [ "$MODE" = "--staged" ]; then
    # 只扫索引：正在准备提交的那批内容
    G() { git grep --cached -I "$@"; }
    LIST() { git diff --cached --name-only; }
else
    # --untracked 必须加：git grep 默认只搜已跟踪文件，
    # 而"新加的文件"正是最容易带进敏感内容的一类。
    # 排除用 ':(exclude)' 形式：':!_Outputs/*' 会被 MSYS 路径转换破坏成 magic '_'。
    G() { git grep -I --untracked "$@" -- ':(exclude)_Outputs/*' ':(exclude)tools/check_secret.sh' 2>/dev/null; }
    # 只列"会进版本库"的文件（已跟踪 + 未忽略的新文件），
    # 否则 .env、备份 SQL 这类被 .gitignore 挡住的本地文件会一直被误报。
    # _Outputs 是流水线过程记录，不属于交付物，一并排除。
    LIST() { { git ls-files; git ls-files --others --exclude-standard; } | sort -u | grep -v "^_Outputs/"; }
fi

HITS=0
report() {
    # $1 标题，$2 命中内容
    if [ -n "$2" ]; then
        echo "!! $1"
        echo "$2" | head -8
        echo ""
        HITS=$((HITS + 1))
    fi
}

echo "=== 敏感信息扫描（模式: $MODE）==="

# 1. 私钥与证书
report "私钥/证书文件" "$(LIST | grep -E '(\.pem|\.p12|\.jks|\.key|id_rsa)$' | head -5)"

# 2. 私钥内容
report "私钥内容" "$(G 'PRIVATE KEY-----' | head -3)"

# 3. JWT（三段式 base64）
report "疑似 JWT token" "$(G -E 'eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}' | cut -c1-120 | head -3)"

# 4. 真实手机号
# 前后不能紧邻 hex 字符：32 位 uid 里碰巧含 11 位数字串的情况要排除掉；
# 演示夹具（138/139 全零、1381234xxxx）白名单跳过。
MOBILES=$(G -hoE "(^|[^0-9a-f])1[3-9][0-9]{9}([^0-9a-f]|$)" 2>/dev/null \
    | grep -oE "1[3-9][0-9]{9}" | sort -u \
    | grep -vE '^1(38|39)0{7}[0-9]$' | grep -vE '^1381234[0-9]{4}$' | grep -vE '^199000[0-9]{5}$' | head -10)
report "疑似真实手机号（需人工判断）" "$MOBILES"

# 5. 明文口令/密钥赋值（忽略占位与变量引用；排除第三方 CSS/JS 里的图标类名如 .fa-user-secret）
report "疑似明文口令" "$(G -nE '(password|passwd|pwd|secret|appsecret|apikey)\s*[:=]\s*[A-Za-z0-9@#_.+/=-]{6,}' \
    | grep -viE '\$\{|\bREDACTED\b|\*\*\*|example|your_|placeholder|xxxx|: root$' \
    | grep -viE '\.(css|min\.js|min\.css):|/lib/|/static/js/lay-module/' \
    | grep -vE '([:=])\s*[a-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)*(\(\))?;?$' \
    | grep -viE 'src/test/|application-test\.yml|test-secret|pwd:string|password:string' \
    | cut -c1-140 | head -6)"

# 6. 不该进库的本地文件
report "本地环境文件/备份/导出" "$(LIST | grep -E '(^|/)\.env$|backup_.*\.sql$|^photoai\.sql$|dump.*\.sql$' | head -6)"

# 7. 大文件（GitHub 单文件 >50MB 会告警，>100MB 直接拒绝）
# 只看会进库的文件：find 不认 .gitignore，会把被忽略的本地大包也报出来。
BIG=$(LIST | while read -r f; do
    [ -f "$f" ] && [ "$(stat -c %s "$f" 2>/dev/null || echo 0)" -gt 5242880 ] && echo "$f"
done | head -5)
report "超过 5MB 的文件" "$BIG"

echo "---"
if [ "$HITS" -eq 0 ]; then
    echo "通过：未发现可疑内容"
    exit 0
fi
echo "发现 $HITS 类可疑内容，请逐项确认后再提交"
exit 1
