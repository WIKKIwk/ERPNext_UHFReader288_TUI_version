#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

if [[ -x "$ROOT_DIR/UhfTuiLinux/dist/start.sh" ]]; then
  exec "$ROOT_DIR/UhfTuiLinux/dist/start.sh"
fi

exec "$ROOT_DIR/UhfTuiLinux/run.sh"
