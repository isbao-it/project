#!/usr/bin/env bash
# 从脚本位置定位仓库；文档、历史证据和构建副本不参与源码守卫。
set -eu
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."

status=0
grep -RInI -E \
  --exclude-dir=.git --exclude-dir=.idea --exclude-dir=node_modules \
  --exclude-dir=target --exclude-dir=_Outputs --exclude-dir=docs --exclude-dir=db \
  'code[[:space:]]*===[[:space:]]*200([^[:digit:]]|$)|code[[:space:]]*==[[:space:]]*200([^[:digit:]]|$)' . || status=$?

case "$status" in
  1) echo 'PASS';;
  0) echo 'FAIL'; exit 1;;
  *) echo 'FAIL: grep 扫描出错'; exit 1;;
esac
