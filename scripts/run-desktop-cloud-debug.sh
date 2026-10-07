#!/usr/bin/env bash
# Debug desktop + cloud backend. See run-desktop-cloud-debug.ps1 on Windows.

set -euo pipefail
# shellcheck source=Desktop-RunCommon.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/Desktop-RunCommon.sh"
apachiy_desktop_run cloud debug "$@"
