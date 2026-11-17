#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
project_root="$(pwd)"
if ! command -v node >/dev/null 2>&1; then
  echo '[ERROR] Node.js 20+ is required' >&2
  exit 3
fi
exec node tools/preflight.js "${1:-$project_root}"
