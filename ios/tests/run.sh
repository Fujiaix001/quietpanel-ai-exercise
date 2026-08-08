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

