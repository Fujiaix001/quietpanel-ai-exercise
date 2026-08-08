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
