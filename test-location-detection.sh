#!/usr/bin/env bash
# Regression check for isLocationInUse's awk command (PLAN.md #A1/#A3).
#
# A JVM unit test cannot run awk, so ConnectionStateCheckerTest.kt only covers the small
# Kotlin-side classification of an already-computed string. This script covers the other half:
# the actual awk command, run for real, against saved real dumpsys appops output.
#
# What this CAN catch: the command silently no longer matching a real dumpsys shape it used to
# match (a logic or syntax regression).
# What this CANNOT catch: the exact class of bug #A1 found (an old device's specific awk BUILD
# crashing on syntax that is otherwise valid) - that only ever showed up running on the real
# broken build (Android 9, API 28's awk, toybox, dated 2012), which this script does not have.
# A1's own fix note in ConnectionStateChecker.kt records how that was found and verified live;
# this script is not a substitute for re-running against that build again if the command ever
# changes - see PLAN.md #A1 for the emulator name already set up for exactly that.
#
# Fixtures are captures from fresh, stock emulator images only, never a real personal device -
# a real device's dumpsys appops output lists every app installed and a timestamped history of
# what each one has accessed, which is not something to commit to a public repo. See PLAN.md #A3.
#
# Usage: ./test-location-detection.sh
# Exits 0 if every fixture's actual answer matches its expected one, non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_FILE="$REPO_ROOT/app/src/main/java/io/github/dorumrr/privacyflip/util/ConnectionStateChecker.kt"
FIXTURE_DIR="$REPO_ROOT/app/src/test/resources/appops-fixtures"

# Extract the exact awk command from the Kotlin source and reverse Kotlin's string escaping
# (\\ -> \, \$ -> $, \" -> "), so this script can never silently drift from what the app ships.
# Carries the command, not a hand-copied guess at it.
extract_command() {
  python3 - "$SOURCE_FILE" << 'PYEOF'
import re, sys
with open(sys.argv[1]) as f:
    text = f.read()
m = re.search(r'"dumpsys appops \| (awk.*?)"\n', text, re.DOTALL)
if not m:
    sys.exit("Could not find the dumpsys appops awk command in " + sys.argv[1])
raw = m.group(1)
out = []
i = 0
while i < len(raw):
    c = raw[i]
    if c == '\\' and i + 1 < len(raw):
        nxt = raw[i+1]
        if nxt == '\\': out.append('\\'); i += 2; continue
        if nxt == '$': out.append('$'); i += 2; continue
        if nxt == '"': out.append('"'); i += 2; continue
    out.append(c)
    i += 1
sys.stdout.write(''.join(out))
PYEOF
}

CMD="$(extract_command)"
if [ -z "$CMD" ]; then
  echo "FAIL: could not extract the command from $SOURCE_FILE - source may have moved or changed shape"
  exit 1
fi

# Runs the command against one fixture and compares against the expected answer. Returns 1 on
# ANY failure: missing fixture, the command itself failing to read it, or a wrong answer - never
# silently treats "got nothing" as if it were a real, checked result.
run_case() {
  local name="$1" fixture="$2" expected="$3"
  if [ ! -f "$fixture" ]; then
    echo "FAIL: $name -> fixture missing: $fixture"
    return 1
  fi
  local actual rc
  set +e
  actual=$(cat "$fixture" | sh -c "$CMD" 2>&1)
  rc=$?
  set -e
  if [ "$rc" -ne 0 ]; then
    echo "FAIL: $name -> command exited $rc reading the fixture, not a real answer: $actual"
    return 1
  fi
  if [ "$actual" = "$expected" ]; then
    echo "PASS: $name"
    return 0
  else
    echo "FAIL: $name -> expected [$expected], got [$actual]"
    return 1
  fi
}

fail_count=0

run_case "idle, stock emulator (API 30)" \
  "$FIXTURE_DIR/idle-api30-emulator.txt" "NONE" || fail_count=$((fail_count+1))

run_case "android's own housekeeping excluded (API 28 emulator)" \
  "$FIXTURE_DIR/android-housekeeping-api28-real.txt" "NONE" || fail_count=$((fail_count+1))

run_case "no location blocks at all -> NOBLOCKS, not NONE" \
  "$FIXTURE_DIR/noblocks-synthetic.txt" "NOBLOCKS" || fail_count=$((fail_count+1))

run_case "genuine third-party app in use -> caught" \
  "$FIXTURE_DIR/active-thirdparty-synthetic.txt" "          Running start at: +5s" || fail_count=$((fail_count+1))

echo ""
if [ "$fail_count" -eq 0 ]; then
  echo "All fixture cases passed."
  echo ""
  # Proves the checks above can actually fail, by calling the SAME run_case function this
  # script relies on - not a disconnected proxy check - with inputs it must reject.
  echo "Self-check (calls run_case itself with inputs it must reject, or this proves nothing):"
  self_fail=0
  if run_case "self-check: deliberately wrong expectation" \
       "$FIXTURE_DIR/noblocks-synthetic.txt" "THIS_IS_NOT_THE_REAL_ANSWER" >/dev/null 2>&1; then
    echo "FAIL: run_case accepted a deliberately wrong expectation"
    self_fail=1
  else
    echo "PASS: run_case correctly rejected a deliberately wrong expectation"
  fi
  if run_case "self-check: fixture that does not exist" \
       "$FIXTURE_DIR/does-not-exist.txt" "NONE" >/dev/null 2>&1; then
    echo "FAIL: run_case accepted a missing fixture"
    self_fail=1
  else
    echo "PASS: run_case correctly rejected a missing fixture"
  fi
  [ "$self_fail" -eq 0 ] || exit 1
  exit 0
else
  echo "$fail_count case(s) failed."
  exit 1
fi
