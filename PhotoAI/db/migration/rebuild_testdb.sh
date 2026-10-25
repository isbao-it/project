#!/usr/bin/env bash
# 用当前生产库结构重建测试库（prod 已迁移到最新版本，测试库与 prod 保持一致）
# 用法：./db/migration/rebuild_testdb.sh [目标库名]  默认 db_pg_test
set -u
MBIN=${MBIN:-"/c/Program Files/MySQL/MySQL Server 8.0/bin"}
DB=${1:-db_pg_test}; SRC=${SRC_DB:-db_pg}
MYSQL="$MBIN/mysql.exe"; DUMP="$MBIN/mysqldump.exe"
OUT=${OUT_DIR:-$(dirname "$0")/../../_Outputs/待审核/TESTDB}
mkdir -p "$OUT"
"$DUMP" -uroot -proot --routines --triggers "$SRC" > "$OUT/dump_$SRC.sql" 2>/dev/null || { echo "FAIL dump"; exit 1; }
"$MYSQL" -uroot -proot -e "DROP DATABASE IF EXISTS \`$DB\`; CREATE DATABASE \`$DB\` CHARACTER SET utf8mb4;" 2>/dev/null || exit 1
"$MYSQL" -uroot -proot "$DB" < "$OUT/dump_$SRC.sql" 2>/dev/null || { echo "FAIL import"; exit 1; }
echo "OK $DB 已按 $SRC 当前结构重建（$(wc -c < "$OUT/dump_$SRC.sql") 字节）"
"$MYSQL" -uroot -proot -N -e "SELECT CONCAT('tables=',COUNT(*)) FROM information_schema.tables WHERE table_schema='$DB' AND table_type='BASE TABLE'" 2>/dev/null
