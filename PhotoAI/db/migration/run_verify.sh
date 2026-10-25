#!/usr/bin/env bash
# C00 一键复跑：从生产库最新 dump 建测试库 → V001 跑两遍 → 12 条断言
# 用法：./db/migration/run_verify.sh [目标测试库名]
#   MBIN    mysql 客户端目录（默认本机 MySQL 8.0 全路径，不在 PATH）
#   SRC_DB  源库（默认 db_pg，只读）
set -u
MBIN=${MBIN:-"/c/Program Files/MySQL/MySQL Server 8.0/bin"}
DB=${1:-db_pg_mig_test}
SRC=${SRC_DB:-db_pg}
OUT_DIR=${OUT_DIR:-$(dirname "$0")/../../_Outputs/待审核/C00/local}
MYSQL="$MBIN/mysql.exe"; DUMP="$MBIN/mysqldump.exe"
Q() { "$MYSQL" -uroot -proot --default-character-set=utf8mb4 -N -e "$1" 2>/dev/null; }

mkdir -p "$OUT_DIR"
echo "== 1/5 备份源库（只读）=="
"$DUMP" -uroot -proot --routines --triggers "$SRC" > "$OUT_DIR/dump_$SRC.sql" 2>/dev/null || { echo "FAIL: dump"; exit 1; }

echo "== 2/5 重建测试库 $DB =="
"$MYSQL" -uroot -proot -e "DROP DATABASE IF EXISTS \`$DB\`; CREATE DATABASE \`$DB\` CHARACTER SET utf8mb4;" 2>/dev/null || exit 1
"$MYSQL" -uroot -proot "$DB" < "$OUT_DIR/dump_$SRC.sql" 2>/dev/null || { echo "FAIL: import"; exit 1; }

BEFORE=$(Q "SELECT COUNT(*) FROM \`$DB\`.pwork")
echo "  迁移前 pwork=$BEFORE"
{
  for t in pwork user comment liketable orderinfo gift; do
    printf "%s\t%s\n" "$t" "$(Q "SELECT COUNT(*) FROM \`$DB\`.\`$t\`")"
  done
} > "$OUT_DIR/counts_before.tsv"

echo "== 3/5 V001 第一遍 =="
"$MYSQL" -uroot -proot --default-character-set=utf8mb4 "$DB" < "$(dirname "$0")/V001__baseline.sql" > "$OUT_DIR/run1.log" 2>&1
E1=$?; echo "  exit=$E1"

echo "== 4/5 V001 第二遍（幂等）=="
"$MYSQL" -uroot -proot --default-character-set=utf8mb4 "$DB" < "$(dirname "$0")/V001__baseline.sql" > "$OUT_DIR/run2.log" 2>&1
E2=$?; echo "  exit=$E2"

{
  for t in pwork user comment liketable orderinfo gift; do
    printf "%s\t%s\n" "$t" "$(Q "SELECT COUNT(*) FROM \`$DB\`.\`$t\`")"
  done
} > "$OUT_DIR/counts_after.tsv"

echo "== 5/5 12 条断言 =="
T=$(mktemp)
{ echo "SET @c00_exit1=$E1; SET @c00_exit2=$E2; SET @c00_pwork_before=$BEFORE;"; cat "$(dirname "$0")/verify_V001.sql"; } > "$T"
"$MYSQL" -uroot -proot --default-character-set=utf8mb4 "$DB" < "$T" > "$OUT_DIR/verify.log" 2>&1
EV=$?
rm -f "$T"
grep -vE "Warning" "$OUT_DIR/verify.log"

echo "-- 计数前后 --"; paste "$OUT_DIR/counts_before.tsv" "$OUT_DIR/counts_after.tsv" | awk -F'\t' '{printf "%-10s %s -> %s\n",$1,$2,$4}'
echo "-- 生产库未动 --"; Q "SELECT CONCAT('pwork=',COUNT(*)) FROM \`$SRC\`.pwork"
Q "SELECT CONCAT('triggers=',COUNT(*)) FROM information_schema.triggers WHERE trigger_schema='$SRC'"
echo "-- 退出码：V001x2=($E1,$E2) verify=$EV --"
[ "$EV" -eq 0 ] && [ "$E1" -eq 0 ] && [ "$E2" -eq 0 ] && echo "RESULT=PASS" || echo "RESULT=FAIL"
exit $EV
