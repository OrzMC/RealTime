#!/usr/bin/env bash
# Proves the updater against a real Paper/Folia server: the plugin downloads a
# "new" build into plugins/update/, refuses a corrupt one, and the server swaps the
# jar in on the next start (Paper's FileProviderSource.checkUpdate behaviour).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="${1:?Usage: smoke-update.sh <paper|folia>}"
MC_VERSION="${MC_VERSION:-26.3}"
RCON_PORT="${RCON_PORT:-25577}"
RCON_PASSWORD="${RCON_PASSWORD:-realtime-ci-rcon-password}"
PROJECT_VERSION="${PROJECT_VERSION:-0.9.0-SNAPSHOT}"
PLUGIN_JAR="$ROOT_DIR/realtime-paper/build/libs/RealTime-${PROJECT_VERSION}.jar"
NEW_VERSION="9.9.9"
BAD_VERSION="9.9.10"

if [[ "$PROJECT" != "paper" && "$PROJECT" != "folia" ]]; then
  echo "Unsupported server project: $PROJECT" >&2
  exit 2
fi
[[ -f "$PLUGIN_JAR" ]] || {
  echo "Missing $PLUGIN_JAR; run ./gradlew :realtime-paper:shadowJar first." >&2
  exit 2
}

WORK_DIR="$(mktemp -d)"
SERVER_PID=""
FIXTURE_PID=""
cleanup() {
  [[ -n "$SERVER_PID" ]] && kill "$SERVER_PID" 2>/dev/null || true
  [[ -n "$FIXTURE_PID" ]] && kill "$FIXTURE_PID" 2>/dev/null || true
  [[ -n "$SERVER_PID" ]] && wait "$SERVER_PID" 2>/dev/null || true
  [[ -n "$FIXTURE_PID" ]] && wait "$FIXTURE_PID" 2>/dev/null || true
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

fail() {
  echo "FAIL: $*" >&2
  echo "--- rtime status ---" >&2
  rcon rtime status >&2 2>&1 || true
  echo "--- plugins/update ---" >&2
  ls -l "$WORK_DIR/plugins/update" >&2 2>&1 || true
  echo "--- server.log (tail) ---" >&2
  tail -n 60 "$WORK_DIR/server.log" >&2 || true
  exit 1
}

start_fixture() {
  local version="$1"
  local corrupt="$2"
  [[ -n "$FIXTURE_PID" ]] && kill "$FIXTURE_PID" 2>/dev/null || true
  [[ -n "$FIXTURE_PID" ]] && wait "$FIXTURE_PID" 2>/dev/null || true
  rm -f "$WORK_DIR/fixture.port"
  local args=(--jar "$PLUGIN_JAR" --version "$version" --port-file "$WORK_DIR/fixture.port")
  [[ "$corrupt" == "true" ]] && args+=(--corrupt-digest)
  python3 "$ROOT_DIR/scripts/update-fixture-server.py" "${args[@]}" >"$WORK_DIR/fixture.log" 2>&1 &
  FIXTURE_PID=$!
  for _ in $(seq 1 50); do
    [[ -s "$WORK_DIR/fixture.port" ]] && break
    sleep 0.2
  done
  [[ -s "$WORK_DIR/fixture.port" ]] || fail "the fixture server did not start"
  FIXTURE_PORT="$(cat "$WORK_DIR/fixture.port")"
  # The plugin reads its API base from modules/updates.yml, so rewrite it with the
  # port this run actually got.
  cat > "$WORK_DIR/plugins/RealTime/modules/updates.yml" <<YAML
config-version: 1
enabled: true
mode: notify
channel: release
check-interval-hours: 6
source: hangar
safety:
  max-download-mb: 16
  skip-dev-builds: false
  allow-insecure-http: true
  api-base-override: 'http://127.0.0.1:${FIXTURE_PORT}'
  allowed-hosts:
    - 127.0.0.1
YAML
}

boot_server() {
  # Only look at lines written by *this* boot: the log accumulates across restarts,
  # and an old "Done (" line would make the wait return before the server is up.
  touch "$WORK_DIR/server.log"
  local from
  from="$(wc -l < "$WORK_DIR/server.log")"
  (
    cd "$WORK_DIR"
    java -Xms1G -Xmx2G -jar server.jar --nogui
  ) >>"$WORK_DIR/server.log" 2>&1 &
  SERVER_PID=$!
  for _ in $(seq 1 150); do
    if tail -n "+$((from + 1))" "$WORK_DIR/server.log" | grep -q 'Done ('; then
      return 0
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      fail "$PROJECT exited before startup completed"
    fi
    sleep 1
  done
  fail "$PROJECT did not become ready within 150 seconds"
}

stop_server() {
  rcon stop >/dev/null 2>&1 || kill "$SERVER_PID" 2>/dev/null || true
  for _ in $(seq 1 60); do
    kill -0 "$SERVER_PID" 2>/dev/null || break
    sleep 1
  done
  kill "$SERVER_PID" 2>/dev/null || true
  wait "$SERVER_PID" 2>/dev/null || true
  SERVER_PID=""
}

rcon() {
  python3 "$ROOT_DIR/scripts/rcon-command.py" 127.0.0.1 "$RCON_PORT" "$RCON_PASSWORD" "$@"
}

wait_for_file() {
  local path="$1"
  for _ in $(seq 1 90); do
    [[ -f "$path" ]] && return 0
    sleep 1
  done
  return 1
}

echo "Resolving latest stable $PROJECT $MC_VERSION build..."
BUILDS="$(curl --fail --silent --show-error --connect-timeout 15 --max-time 30 \
  -H 'User-Agent: OrzMC-RealTime-CI/1.0 (https://github.com/OrzMC/RealTime)' \
  "https://fill.papermc.io/v3/projects/$PROJECT/versions/$MC_VERSION/builds")"
DOWNLOAD_URL="$(jq -r 'first(.[] | select(.channel == "STABLE") | .downloads."server:default".url) // empty' <<< "$BUILDS")"
SHA256_EXPECTED="$(jq -r 'first(.[] | select(.channel == "STABLE") | .downloads."server:default".checksums.sha256) // empty' <<< "$BUILDS")"
[[ -n "$DOWNLOAD_URL" && -n "$SHA256_EXPECTED" ]] || {
  echo "No stable $PROJECT $MC_VERSION build with SHA-256 metadata is available." >&2
  exit 1
}
CACHE_DIR="${TMPDIR:-/tmp}/orzmc-realtime-smoke-cache"
mkdir -p "$CACHE_DIR"
CACHED_SERVER="$CACHE_DIR/$PROJECT-$MC_VERSION.jar"
if [[ -f "$CACHED_SERVER" ]] && echo "$SHA256_EXPECTED  $CACHED_SERVER" | sha256sum --check --status -; then
  echo "Reusing the cached server artifact."
  cp "$CACHED_SERVER" "$WORK_DIR/server.jar"
else
  echo "Downloading server artifact..."
  curl --fail --location --silent --show-error --connect-timeout 15 --max-time 180 "$DOWNLOAD_URL" -o "$WORK_DIR/server.jar"
  cp "$WORK_DIR/server.jar" "$CACHED_SERVER"
fi
echo "$SHA256_EXPECTED  $WORK_DIR/server.jar" | sha256sum --check -
echo "Server artifact checksum verified."

mkdir -p "$WORK_DIR/plugins/RealTime/modules" "$WORK_DIR/plugins/RealTime/data"
cp "$PLUGIN_JAR" "$WORK_DIR/plugins/RealTime.jar"
printf 'schema-version: 7\ndevices: []\ncleanup-jobs: []\n' > "$WORK_DIR/plugins/RealTime/data/devices.yml"
printf 'enabled: true\n' > "$WORK_DIR/plugins/RealTime/modules/redstone.yml"
printf 'eula=true\n' > "$WORK_DIR/eula.txt"
cat > "$WORK_DIR/server.properties" <<PROPERTIES
server-port=25567
online-mode=false
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASSWORD
broadcast-rcon-to-ops=false
view-distance=2
simulation-distance=2
PROPERTIES

JAR_SHA="$(sha256sum "$PLUGIN_JAR" | cut -d' ' -f1)"

# --- 1) download a newer build into the update folder -------------------------
start_fixture "$NEW_VERSION" false
boot_server
grep -q 'Enabling RealTime' "$WORK_DIR/server.log" || fail "RealTime did not enable"

DOWNLOAD_OUTPUT="$(rcon rtime update download 2>&1 || true)"
echo "update download answered: $DOWNLOAD_OUTPUT"
PENDING="$WORK_DIR/plugins/update/RealTime-${NEW_VERSION}.jar"
wait_for_file "$PENDING" || fail "the updater did not write $PENDING"
PENDING_SHA="$(sha256sum "$PENDING" | cut -d' ' -f1)"
[[ "$PENDING_SHA" == "$JAR_SHA" ]] || fail "downloaded jar checksum $PENDING_SHA does not match $JAR_SHA"
grep -q "saved as update/RealTime-${NEW_VERSION}.jar" "$WORK_DIR/server.log" \
  || fail "the updater did not log the verified download"
# No partial file may survive a successful download.
if compgen -G "$WORK_DIR/plugins/update/*.part" >/dev/null; then
  fail "a .part file was left behind in the update folder"
fi
echo "download + checksum verified"

# --- 2) restart: Paper swaps the pending jar in -------------------------------
stop_server
boot_server
[[ -f "$WORK_DIR/plugins/RealTime-${NEW_VERSION}.jar" ]] \
  || fail "Paper did not apply plugins/update (expected plugins/RealTime-${NEW_VERSION}.jar)"
[[ ! -f "$WORK_DIR/plugins/RealTime.jar" ]] \
  || fail "the previous jar name is still present, so two copies would load"
if compgen -G "$WORK_DIR/plugins/update/RealTime-*.jar" >/dev/null; then
  fail "the update folder still holds a candidate after the swap"
fi
grep -q 'Enabling RealTime' "$WORK_DIR/server.log" || fail "RealTime did not enable after the swap"
echo "Paper applied the pending update on restart"

# --- 3) a corrupt download is refused ----------------------------------------
start_fixture "$BAD_VERSION" true
# The fixture restarted on a new port, so re-read modules/updates.yml.
rcon rtime reload >/dev/null 2>&1 || true
sleep 1
rcon rtime update download >/dev/null 2>&1 || true
sleep 10
if [[ -f "$WORK_DIR/plugins/update/RealTime-${BAD_VERSION}.jar" ]]; then
  fail "a jar with a mismatched checksum was accepted"
fi
if compgen -G "$WORK_DIR/plugins/update/*.part" >/dev/null; then
  fail "a rejected download left a .part file behind"
fi
grep -q "checksum mismatch" "$WORK_DIR/server.log" || fail "the corrupt download was not reported"
echo "corrupt download refused"

echo "$PROJECT $MC_VERSION update smoke test passed (download, restart swap, corrupt refusal)."
