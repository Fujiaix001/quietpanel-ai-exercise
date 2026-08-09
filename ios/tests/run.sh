#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d)
[ -n "$test_dir" ] && [ -d "$test_dir" ]
trap 'rm -rf -- "$test_dir"' EXIT HUP INT TERM

${CC:-cc} -std=c11 -Wall -Wextra -Werror \
  -DLEGACY_PROTOCOL_TEST -x c "$repo_dir/main.m" \
  -o "$test_dir/protocol-test"
"$test_dir/protocol-test"

grep -q '^Package: tw.codex.quietpanel$' "$repo_dir/control"
grep -q '^APPLICATION_NAME = QuietPanel$' "$repo_dir/Makefile"
grep -q '<string>tw.codex.quietpanel</string>' "$repo_dir/Resources/Info.plist"
grep -q 'kLegacyPort = 9001;' "$repo_dir/main.m"
if grep -q 'tw.codex.legacypaddisplay' "$repo_dir/control" "$repo_dir/Resources/Info.plist"; then
  echo 'standalone identity check failed' >&2
  exit 1
fi
echo 'standalone identity: ok'

page_transition=$(awk '
  $0 == "- (void)setDisplayActive:(BOOL)active page:(NSInteger)page {" { capture = 1 }
  capture { print }
  capture && /^}$/ { exit }
' "$repo_dir/main.m")
case "$page_transition" in
  *'dispatch_async(_queue'*)
    echo 'page transition must not wait behind the blocking receive loop' >&2
    exit 1
    ;;
esac
printf '%s\n' "$page_transition" | grep -q '@synchronized (self)'
printf '%s\n' "$page_transition" | grep -q '@"page": @(page)'
echo 'page transition queue: ok'

if grep -q 'kQuietClockLayoutKey\|photoClockLayoutStyle\|切換版面' "$repo_dir/main.m"; then
  echo 'alternate photo-clock layout still present' >&2
  exit 1
fi
grep -q 'systemFontOfSize:16.0 weight:UIFontWeightSemibold' "$repo_dir/main.m"
grep -q 'updateClockTimerForCurrentPage' "$repo_dir/main.m"
grep -q 'targetSize:targetSize' "$repo_dir/main.m"
grep -q 'CGRectMake(193, 211, 209, 3)' "$repo_dir/main.m"
grep -q 'self.view.bounds.size.width - 452, 22, 420, 220' "$repo_dir/main.m"
grep -q 'initWithFrame:CGRectMake(140, 0, 280, 220)' "$repo_dir/main.m"
grep -q '_photoClockDrag.minimumPressDuration = 0.35' "$repo_dir/main.m"
grep -q 'requireGestureRecognizerToFail:_photoClockDrag' "$repo_dir/main.m"
grep -q 'sendTouchPhase:@"began"' "$repo_dir/main.m"
if grep -q '防誤觸已開啟\|只有快捷鍵可操作' "$repo_dir/main.m"; then
  echo 'shortcut page still contains redundant guard copy' >&2
  exit 1
fi
echo 'photo clock UX: ok'
