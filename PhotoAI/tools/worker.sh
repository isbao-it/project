#!/usr/bin/env bash
# 统一派工入口：先确认梯子（chatgpt.com）可用，再执行命令。
# 用法：bash tools/worker.sh <日志名> -- <命令...>
#   WAIT_MIN=5 最多等多少分钟（默认 5；超时就退出，由 Hermes 提醒用户看梯子）
#   PROXY=http://127.0.0.1:7897
# 退出码：0=命令跑过（命令自己的码另存 worker 日志）；90=等梯子超时
set -u
NAME=${1:?日志名}; shift
[ "${1:-}" = "--" ] && shift
WAIT_MIN=${WAIT_MIN:-5}   # 用户 2026-10-23 要求：最多等 5 分钟就提醒/退出，不许长时间静默等待
PROXY=${PROXY:-http://127.0.0.1:7897}
LOG_DIR=${LOG_DIR:-"C:/work/dev/projects/PhotoAI Hub/_Outputs/待审核/SUPERVISOR"}
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/worker-$NAME.log"
say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }

probe() {
    local c
    c=$(curl -s -o /dev/null -w "%{http_code}" --max-time 8 -x "$PROXY" \
        https://chatgpt.com/backend-api/me 2>/dev/null)
    case "$c" in 401|403) return 0 ;; *) echo "$c"; return 1 ;; esac
}

say "=== 派工 $NAME 开始（最多等梯子 ${WAIT_MIN} 分钟）==="
deadline=$(( $(date +%s) + WAIT_MIN * 60 ))
while :; do
    if probe >/dev/null; then say "梯子可用（chatgpt.com 401/403）"; break; fi
    code=$(probe)
    say "梯子不可用（me=$code），20 秒后重试"
    if [ "$(date +%s)" -ge "$deadline" ]; then
        say "⚠️ 等待超过 ${WAIT_MIN} 分钟，梯子仍未恢复 —— 请提醒用户检查 Clash，本次派工已放弃（可原样重跑）"
        exit 90
    fi
    sleep 20
done

say "执行：$*"
export HTTP_PROXY="$PROXY" HTTPS_PROXY="$PROXY" NO_PROXY=localhost,127.0.0.1
"$@" >> "$LOG" 2>&1
rc=$?
say "命令退出码=$rc（完整输出见 $LOG）"
exit 0
