#!/usr/bin/env bash
# 本地联调后端一键启动（dev profile + mock 登录）。
# 用法：bash tools/dev-backend.sh            # 前台运行
#       bash tools/dev-backend.sh --rebuild  # 先打包再起
# 说明：dev profile 才会认 mock: 前缀；WX_MOCK_ENABLED 必须为 true。
set -u
cd "$(dirname "$0")/../Backend" || exit 1
export JAVA_HOME="/c/develop-tools/JDK/jdk1.8.0_504"   # 项目按 JDK8 编译，强制用 8（继承的 JAVA_HOME 可能是 21）
export DB_USER=${DB_USER:-root}
export DB_PASSWORD=${DB_PASSWORD:-root}
export DB_NAME=${DB_NAME:-db_pg}
export JWT_SECRET=${JWT_SECRET:-MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=}
export WX_MOCK_ENABLED=true
export WX_MSG_SEC_CHECK_ENABLED=false

if [ "${1:-}" = "--rebuild" ] || [ ! -f target/pg-0.0.1-SNAPSHOT.jar ]; then
    echo "[dev-backend] 打包中..."
    "$TMPDIR/mvn8" -B -ntp -q clean package -DskipTests || exit 1
fi

# 预检 MySQL：服务停了就直接起，否则后端会以 Connection refused 反复重启失败。
if ! netstat -ano | grep -q ":3306 .*LISTENING"; then
    echo "[dev-backend] MySQL80 未监听 3306，尝试启动服务..."
    net start MySQL80 >/dev/null 2>&1 || echo "[dev-backend] 启动失败，请手动以管理员身份运行: net start MySQL80"
    for _ in $(seq 1 15); do netstat -ano | grep -q ":3306 .*LISTENING" && break; sleep 1; done
fi
netstat -ano | grep -q ":3306 .*LISTENING" || { echo "[dev-backend] 3306 仍不可用，退出"; exit 1; }

echo "[dev-backend] 启动 dev profile（mock 登录已开）→ http://localhost:8082"
exec "$JAVA_HOME/bin/java" -jar target/pg-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
