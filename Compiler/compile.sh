#!/usr/bin/env bash
# Ratpack Compiler — convenience wrapper.
#
#   ./compile.sh victim.apk                 repack with ./setting.json
#   ./compile.sh victim.apk -o out.apk       choose the output name
#   ./compile.sh --rebuild-payload victim.apk
#
# The toolchain comes from /home/aglegend/tools/env.sh when it exists.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [[ -f /home/aglegend/tools/env.sh ]]; then
  # shellcheck disable=SC1091
  source /home/aglegend/tools/env.sh
fi

PY="${PYTHON:-python3}"
exec "$PY" "$SCRIPT_DIR/ratpack/compile.py" "$@"
