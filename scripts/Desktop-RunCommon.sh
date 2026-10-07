# Source from run-desktop-*.sh — do not execute directly.

apachiy_desktop_run() {
    local backend="$1"
    local build_type="$2"
    shift 2

    local root_dir script_dir
    script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    root_dir="$(cd "$script_dir/.." && pwd)"
    local dev_props="$root_dir/local.dev.properties"
    local dev_example="$root_dir/local.dev.example.properties"
    local local_props="$root_dir/local.properties"
    local gradlew="$root_dir/gradlew"

    local run_task use_local_dev backend_label
    if [[ "$build_type" == "release" ]]; then
        run_task="runRelease"
    else
        run_task="run"
    fi

    if [[ "$backend" == "local" ]]; then
        if [[ ! -f "$dev_props" ]]; then
            if [[ ! -f "$dev_example" ]]; then
                echo "Missing $dev_example" >&2
                return 1
            fi
            cp "$dev_example" "$dev_props"
            echo "Created local.dev.properties from template. Edit backend URLs/keys if needed."
        fi
        export APACHIY_USE_LOCAL_DEV=1
        use_local_dev="true"
        backend_label="local.dev.properties overlay"
    else
        if [[ ! -f "$local_props" ]]; then
            echo "Missing $local_props" >&2
            return 1
        fi
        unset APACHIY_USE_LOCAL_DEV 2>/dev/null || true
        use_local_dev="false"
        backend_label="local.properties (cloud)"
    fi

    echo "Starting desktop ($build_type) with $backend_label."
    exec "$gradlew" ":composeApp:$run_task" "-Pnuvio.useLocalDev=$use_local_dev" "$@"
}
