#!/usr/bin/env bash
# Alias for run-desktop-cloud-debug.sh

set -euo pipefail
exec "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/run-desktop-cloud-debug.sh" "$@"
