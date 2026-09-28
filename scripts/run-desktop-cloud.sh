#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_PROPS="$ROOT_DIR/local.properties"
GRADLEW="$ROOT_DIR/gradlew"

if [[ ! -f "$LOCAL_PROPS" ]]; then
    echo "Missing $LOCAL_PROPS" >&2
    exit 1
fi

unset APACHIY_USE_LOCAL_DEV 2>/dev/null || true

echo "Starting desktop with local.properties (cloud)."

exec "$GRADLEW" ":composeApp:run" "-Pnuvio.useLocalDev=false" "$@"
