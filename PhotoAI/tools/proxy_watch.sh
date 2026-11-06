#!/usr/bin/env bash
# 梯子哨兵：每 30 秒探测一次，状态变化（可用<->不可用）时写一条记录。
# 用法：bash tools/proxy_watch.sh [分钟数，默认 240]
# 日志：_Outputs/待审核/SUPERVISOR/proxy-watch.log
PROXY=${PROXY:-http://127.0.0.1:7897}
MINS=${1:-240}
LOG_DIR=${LOG_DIR:-"C:/work/dev/projects/PhotoAI Hub/_Outputs/待审核/SUPERVISOR"}
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/proxy-watch.log"
last=""
end=$(( $(date +%s) + MINS * 60 ))
while [ "$(date +%s)" -lt "$end" ]; do
    c=$(curl -s -o /dev/null -w "%{http_code}" --max-time 8 -x "$PROXY" \
        https://chatgpt.com/backend-api/me 2>/dev/null)
    case "$c" in 401|403) st=UP ;; *) st=DOWN ;; esac
    if [ "$st" != "$last" ]; then
        echo "[$(date +%Y-%m-%d\ %H:%M:%S)] 梯子 $st（me=$c）" >> "$LOG"
        last=$st
    fi
    sleep 30
done
echo "[$(date +%Y-%m-%d\ %H:%M:%S)] 哨兵退出（${MINS} 分钟到）" >> "$LOG"
