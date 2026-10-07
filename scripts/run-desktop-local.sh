#!/usr/bin/env bash
# Alias for run-desktop-local-debug.sh

set -euo pipefail
exec "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/run-desktop-local-debug.sh" "$@"
