#!/usr/bin/env bash
# Release desktop + cloud backend. See run-desktop-cloud-release.ps1 on Windows.

set -euo pipefail
# shellcheck source=Desktop-RunCommon.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/Desktop-RunCommon.sh"
apachiy_desktop_run cloud release "$@"
