#!/usr/bin/env bash
# 卡级监管器：等测试落盘 → 编译 → 跑红 → 判 0 error → 自动派实现 → 跑验收 → 停在"待人工终审"
# 用法：bash tools/supervise_card.sh <卡号> <worktree目录> <测试类逗号分隔> <测试库名> <实现prompt文件> <日志目录>
set -u
CARD=$1; WT=$2; TESTS=$3; DB=$4; PROMPT=$5; LOG=${6:-"_Outputs/待审核/SUPERVISOR"}
MBIN=${MBIN:-"/c/Program Files/MySQL/MySQL Server 8.0/bin"}
JAVA_HOME_DIR=${JAVA_HOME_DIR:-"/c/develop-tools/JDK/jdk1.8.0_504"}
MVN="/c/Users/朱柏缘/AppData/Local/hermes/skills/windows-java-maven-jdk8/scripts/mvn8"
mkdir -p "$LOG"
# 关键：后面会 cd 进 worktree，相对路径会失效 → 先转绝对路径
LOG="$(cd "$LOG" && pwd)"
SRC_ABS="$(cd "${SRC:-$(dirname "$0")/..}" && pwd)"
PROMPT="$(cd "$(dirname "$PROMPT")" && pwd)/$(basename "$PROMPT")"
say() { echo "[$(date +%H:%M:%S)] [$CARD] $*" | tee -a "$LOG/card$CARD.log"; }

say "监管启动 worktree=$WT tests=$TESTS db=$DB"

# 1) 等测试文件在主仓落盘（qoder 写在主仓），齐了再拷进 worktree
SRC=${SRC_ABS}
IFS=',' read -ra TARR <<< "$TESTS"
for i in $(seq 1 270); do
  missing=0
  for t in "${TARR[@]}"; do
    [ -f "$SRC/Backend/src/test/java/com/pg/$t.java" ] || missing=$((missing+1))
  done
  if [ "$missing" -eq 0 ]; then say "测试文件齐了（$TESTS），拷入 worktree"; break; fi
  say "等待测试文件，缺 $missing 个"
  sleep 20
done
for t in "${TARR[@]}"; do
  cp "$SRC/Backend/src/test/java/com/pg/$t.java" "$WT/Backend/src/test/java/com/pg/$t.java" || { say "拷 $t 失败，退出"; exit 1; }
done

# 2) 编译 + 跑红
cd "$WT/Backend" || { say "worktree 不存在，退出"; exit 1; }
export JAVA_HOME="$JAVA_HOME_DIR"
say "test-compile…"
"$MVN" -B -ntp test-compile > "$LOG/card$CARD-compile.log" 2>&1 || { say "编译失败，见 card$CARD-compile.log —— 停止"; exit 2; }
say "编译通过，跑红…"
"$MVN" -B -ntp test -Dtest="$TESTS" -DfailIfNoTests=false > "$LOG/card$CARD-red.log" 2>&1
FAILS=$(grep -aE "^\[ERROR\] Tests run: [0-9]+, Failures" "$LOG/card$CARD-red.log" | tail -1 | grep -oE "Failures: [0-9]+, Errors: [0-9]+")
say "红态：$FAILS"
ERRS=$(echo "$FAILS" | grep -oE "Errors: [0-9]+" | grep -oE "[0-9]+")
if [ "${ERRS:-1}" -ne 0 ]; then
  say "红态不干净（Errors=$ERRS），说明是测试骨架问题，停止等人看"
  echo "STATUS=TEST_BROKEN" >> "$LOG/card$CARD.status"
  exit 3
fi

# 3) 自动派实现
say "红态干净，自动派实现（codex sol high）"
codex exec -m gpt-6.1-sol -c model_reasoning_effort="high" -c sandbox_mode="workspace-write" \
  -c approval_policy="never" -C "$WT" --skip-git-repo-check \
  -o "$LOG/card$CARD-impl-summary.md" "$(cat "$PROMPT")" > "$LOG/card$CARD-impl.log" 2>&1
say "实现进程退出码=$?"

# 4) 验收：定向 + 全量
"$MVN" -B -ntp test -Dtest="$TESTS" -DfailIfNoTests=false > "$LOG/card$CARD-green.log" 2>&1
G=$(grep -aE "^\[(ERROR|INFO)\] Tests run: [0-9]+, Failures" "$LOG/card$CARD-green.log" | tail -1)
say "定向结果：$G"
"$MVN" -B -ntp test > "$LOG/card$CARD-full.log" 2>&1
F=$(grep -aE "^\[(ERROR|INFO)\] Tests run: [0-9]+, Failures" "$LOG/card$CARD-full.log" | tail -1)
say "全量结果：$F"
echo "STATUS=READY_FOR_REVIEW" >> "$LOG/card$CARD.status"
say "完成，等待人工终审"
