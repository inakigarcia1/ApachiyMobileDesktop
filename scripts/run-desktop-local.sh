#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEV_PROPS="$ROOT_DIR/local.dev.properties"
DEV_EXAMPLE="$ROOT_DIR/local.dev.example.properties"
GRADLEW="$ROOT_DIR/gradlew"

if [[ ! -f "$DEV_PROPS" ]]; then
    if [[ ! -f "$DEV_EXAMPLE" ]]; then
        echo "Missing $DEV_EXAMPLE" >&2
        exit 1
    fi
    cp "$DEV_EXAMPLE" "$DEV_PROPS"
    echo "Created local.dev.properties from template. Edit backend URLs/keys if needed."
fi

export APACHIY_USE_LOCAL_DEV=1

echo "Starting desktop with local.dev.properties overlay (cloud local.properties unchanged)."

exec "$GRADLEW" ":composeApp:run" "-Pnuvio.useLocalDev=true" "$@"
