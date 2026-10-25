#!/usr/bin/env bash
# V002 命名统一一键复跑：dump → 建库 → 记录行数快照 → V002 跑两遍 → 13 条断言
# 用法：./db/migration/run_verify_v002.sh [目标库名]
set -u
MBIN=${MBIN:-"/c/Program Files/MySQL/MySQL Server 8.0/bin"}
DB=${1:-db_pg_naming_test}
SRC=${SRC_DB:-db_pg}
OUT_DIR=${OUT_DIR:-$(dirname "$0")/../../_Outputs/待审核/NAMING/local}
MYSQL="$MBIN/mysql.exe"; DUMP="$MBIN/mysqldump.exe"
Q() { "$MYSQL" -uroot -proot --default-character-set=utf8mb4 -N -e "$1" 2>/dev/null; }

mkdir -p "$OUT_DIR"
echo "== 1/5 dump 源库 $SRC（只读）=="
"$DUMP" -uroot -proot --routines --triggers "$SRC" > "$OUT_DIR/dump_$SRC.sql" 2>/dev/null || { echo "FAIL: dump"; exit 1; }

echo "== 2/5 重建 $DB 并导入 =="
"$MYSQL" -uroot -proot -e "DROP DATABASE IF EXISTS \`$DB\`; CREATE DATABASE \`$DB\` CHARACTER SET utf8mb4;" 2>/dev/null || exit 1
"$MYSQL" -uroot -proot "$DB" < "$OUT_DIR/dump_$SRC.sql" 2>/dev/null || { echo "FAIL: import"; exit 1; }

echo "== 3/5 记录迁移前各表行数 =="
TABLES=$(Q "SELECT GROUP_CONCAT(table_name) FROM information_schema.tables WHERE table_schema='$DB' AND table_type='BASE TABLE' AND table_name NOT IN ('pclist','waudit')")
BEFORE=$(Q "SELECT JSON_OBJECT($(echo "$TABLES" | tr ',' '\n' | sed "s/.*/'&', (SELECT COUNT(*) FROM \\\`$DB\\\`.\\\`&\\\`)/" | paste -sd, -))")
echo "$BEFORE" > "$OUT_DIR/counts_before.json"
echo "  表数=$(echo "$TABLES" | tr ',' '\n' | wc -l)"

echo "== 4/5 V002 连跑两遍 =="
"$MYSQL" -uroot -proot --default-character-set=utf8mb4 "$DB" < "$(dirname "$0")/V002__naming.sql" > "$OUT_DIR/v002_run1.log" 2>&1; E1=$?
"$MYSQL" -uroot -proot --default-character-set=utf8mb4 "$DB" < "$(dirname "$0")/V002__naming.sql" > "$OUT_DIR/v002_run2.log" 2>&1; E2=$?
echo "  exit1=$E1 exit2=$E2"

echo "== 5/5 13 条断言 =="
T=$(mktemp)
{ echo "SET @naming_exit1=$E1; SET @naming_exit2=$E2; SET @naming_before='$(echo "$BEFORE" | sed "s/'/''/g")';"; cat "$(dirname "$0")/verify_V002.sql"; } > "$T"
"$MYSQL" -uroot -proot --default-character-set=utf8mb4 "$DB" < "$T" > "$OUT_DIR/verify_v002.log" 2>&1
EV=$?; rm -f "$T"
grep -vE "Warning" "$OUT_DIR/verify_v002.log"

echo "-- 源库未动 --"; Q "SELECT CONCAT('follow_cols=', GROUP_CONCAT(column_name)) FROM information_schema.columns WHERE table_schema='$SRC' AND table_name='follow'"
echo "-- 退出码 V002x2=($E1,$E2) verify=$EV --"
[ "$EV" -eq 0 ] && [ "$E1" -eq 0 ] && [ "$E2" -eq 0 ] && echo "RESULT=PASS" || echo "RESULT=FAIL"
exit $EV
