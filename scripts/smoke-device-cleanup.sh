#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="${1:?Usage: smoke-device-cleanup.sh <paper|folia>}"
MC_VERSION="${MC_VERSION:-26.3}"
RCON_PORT="${RCON_PORT:-25576}"
RCON_PASSWORD="${RCON_PASSWORD:-realtime-ci-cleanup-password}"
PLUGIN_JAR="${ROOT_DIR}/realtime-paper/build/libs/RealTime-${PROJECT_VERSION:-0.1.0-SNAPSHOT}.jar"

if [[ "$PROJECT" != "paper" && "$PROJECT" != "folia" ]]; then
  echo "Unsupported server project: $PROJECT" >&2
  exit 2
fi
if [[ ! -f "$PLUGIN_JAR" ]]; then
  echo "Plugin JAR not found: $PLUGIN_JAR" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d)"
SERVER_PID=""
cleanup() {
  if [[ -n "$SERVER_PID" ]]; then
    rcon stop >/dev/null 2>&1 || true
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

API_URL="https://fill.papermc.io/v3/projects/$PROJECT/versions/$MC_VERSION/builds"
echo "Resolving latest stable $PROJECT $MC_VERSION build..."
BUILDS="$(curl --fail --silent --show-error --connect-timeout 15 --max-time 30 -H 'User-Agent: OrzMC-RealTime-CI/1.0 (https://github.com/OrzMC/RealTime)' "$API_URL")"
DOWNLOAD_URL="$(jq -r 'first(.[] | select(.channel == "STABLE") | .downloads."server:default".url) // empty' <<< "$BUILDS")"
SHA256="$(jq -r 'first(.[] | select(.channel == "STABLE") | .downloads."server:default".checksums.sha256) // empty' <<< "$BUILDS")"
if [[ -z "$DOWNLOAD_URL" || -z "$SHA256" ]]; then
  echo "No stable $PROJECT $MC_VERSION build with SHA-256 metadata is available." >&2
  exit 1
fi

curl --fail --location --silent --show-error --connect-timeout 15 --max-time 180 "$DOWNLOAD_URL" -o "$WORK_DIR/server.jar"
echo "$SHA256  $WORK_DIR/server.jar" | sha256sum --check -
mkdir -p "$WORK_DIR/plugins/RealTime/data"
cp "$PLUGIN_JAR" "$WORK_DIR/plugins/RealTime.jar"
cat > "$WORK_DIR/plugins/RealTime/data/devices.yml" <<'YAML'
schema-version: 2
devices: []
cleanup-jobs: []
YAML
printf 'eula=true\n' > "$WORK_DIR/eula.txt"
cat > "$WORK_DIR/server.properties" <<PROPERTIES
server-port=25567
level-name=world
online-mode=false
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASSWORD
broadcast-rcon-to-ops=false
view-distance=3
simulation-distance=3
PROPERTIES

start_server() {
  (
    cd "$WORK_DIR"
    java -Xms1G -Xmx2G -jar server.jar --nogui
  ) >> "$WORK_DIR/server.log" 2>&1 &
  SERVER_PID=$!
  echo "Waiting for $PROJECT server startup..."
  for _ in $(seq 1 150); do
    if grep -q 'Done (' "$WORK_DIR/server.log"; then
      return 0
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      cat "$WORK_DIR/server.log"
      echo "$PROJECT server exited before startup completed." >&2
      return 1
    fi
    sleep 1
  done
  cat "$WORK_DIR/server.log"
  echo "$PROJECT server did not become ready within 150 seconds." >&2
  return 1
}

stop_server() {
  rcon stop >/dev/null
  for _ in $(seq 1 45); do
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      wait "$SERVER_PID" || true
      SERVER_PID=""
      return 0
    fi
    sleep 1
  done
  echo "$PROJECT server did not stop cleanly." >&2
  return 1
}

rcon() {
  python3 "$ROOT_DIR/scripts/rcon-command.py" 127.0.0.1 "$RCON_PORT" "$RCON_PASSWORD" --no-expect "$@"
}

rtime() {
  python3 "$ROOT_DIR/scripts/rcon-command.py" 127.0.0.1 "$RCON_PORT" "$RCON_PASSWORD" "$@"
}

# First boot creates the world and its stable UUID. Seed output blocks, then
# stop cleanly so the second boot exercises durable cleanup recovery.
start_server
grep -q 'Enabling RealTime' "$WORK_DIR/server.log" || {
  cat "$WORK_DIR/server.log"
  echo "RealTime did not enable on initial $PROJECT boot." >&2
  exit 1
}

rcon forceload add 0 0 >/dev/null
rcon forceload add 1600 0 >/dev/null
for position in "0 64 0" "1600 64 0"; do
  MARKER="REALTIME_SEED_CHUNK_LOADED_${position// /_}"
  LOADED=0
  for _ in $(seq 1 20); do
    rcon execute if loaded $position run say "$MARKER" >/dev/null || true
    if grep -Fq "$MARKER" "$WORK_DIR/server.log"; then
      LOADED=1
      break
    fi
    sleep 1
  done
  if [[ "$LOADED" != "1" ]]; then
    cat "$WORK_DIR/server.log"
    echo "Could not load seed position $position on $PROJECT." >&2
    exit 1
  fi
done

rcon setblock 0 63 0 minecraft:stone
rcon setblock 0 64 0 'minecraft:redstone_wire[power=15]'
for bit in $(seq 1 14); do
  x=$((1600 + bit))
  rcon setblock "$x" 63 0 minecraft:stone
  rcon setblock "$x" 64 0 'minecraft:redstone_wire[power=15]'
done
rcon setblock 1615 63 0 minecraft:stone
rcon setblock 1615 64 0 minecraft:stone
rcon forceload remove 0 0 >/dev/null
rcon forceload remove 1600 0 >/dev/null
sleep 5
stop_server

UID_FILE="$(find "$WORK_DIR" -type f -name uid.dat -print -quit)"
if [[ -z "$UID_FILE" ]]; then
  find "$WORK_DIR" -maxdepth 3 -type d -print
  echo "World uid.dat was not created by the first $PROJECT boot." >&2
  exit 1
fi
WORLD_DIR="$(dirname "$UID_FILE")"
WORLD_NAME="$(basename "$WORLD_DIR")"
WORLD_UUID="$(python3 - "$UID_FILE" <<'PY'
import pathlib
import sys
import uuid

path = pathlib.Path(sys.argv[1])
raw = path.read_bytes()
if len(raw) != 16:
    raise SystemExit(f"Unexpected uid.dat length: {len(raw)}")
print(uuid.UUID(bytes=raw))
PY
)"
DEVICE_ID="00000000-0000-0000-0000-000000000001"
JOB_ID="00000000-0000-0000-0000-000000000002"
{
  printf 'schema-version: 2\ndevices: []\ncleanup-jobs:\n'
  printf '  - id: %s\n' "$JOB_ID"
  printf '    device-id: %s\n' "$DEVICE_ID"
  printf '    device-name: ci-restart-cleanup\n'
  printf '    world: %s\n' "$WORLD_NAME"
  printf '    world-uuid: %s\n' "$WORLD_UUID"
  printf '    outputs:\n'
  for bit in $(seq 0 15); do
    if [[ "$bit" == "0" ]]; then
      x=0
    else
      x=$((1600 + bit))
    fi
    printf '      - bit-index: %s\n        x: %s\n        y: 64\n        z: 0\n        status: PENDING\n        detail: null\n' "$bit" "$x"
  done
} > "$WORK_DIR/plugins/RealTime/data/devices.yml"

echo "Restarting $PROJECT with a persisted cleanup job for world UUID $WORLD_UUID..."
: > "$WORK_DIR/server.log"
start_server
grep -q 'Enabling RealTime' "$WORK_DIR/server.log" || {
  cat "$WORK_DIR/server.log"
  echo "RealTime did not enable after restart on $PROJECT." >&2
  exit 1
}
rcon forceload add 0 0 >/dev/null

PENDING_READY=0
for _ in $(seq 1 30); do
  QUEUE="$(rtime device cleanup list)"
  if grep -Fq '待处理：15' <<< "$QUEUE" && grep -Fq '需人工处理：0' <<< "$QUEUE"; then
    PENDING_READY=1
    break
  fi
  sleep 1
done
if [[ "$PENDING_READY" != "1" ]]; then
  cat "$WORK_DIR/server.log"
  cat "$WORK_DIR/plugins/RealTime/data/devices.yml"
  echo "Restart recovery did not clear only the loaded spawn output on $PROJECT: $QUEUE" >&2
  exit 1
fi

rcon execute unless loaded 1600 64 0 run say REALTIME_CLEANUP_CHUNK_STILL_UNLOADED >/dev/null || true
grep -Fq 'REALTIME_CLEANUP_CHUNK_STILL_UNLOADED' "$WORK_DIR/server.log" || {
  echo "Cleanup processing unexpectedly loaded the deferred output chunk on $PROJECT: $UNLOADED_CHECK" >&2
  exit 1
}

# The remaining 15 outputs live in chunk (100, 0), which must remain unloaded
# until this command. Loading it externally should fire ChunkLoadEvent and retry.
rcon forceload add 1600 0 >/dev/null
ATTENTION_READY=0
for _ in $(seq 1 30); do
  QUEUE="$(rtime device cleanup list)"
  if grep -Fq '待处理：0' <<< "$QUEUE" && grep -Fq '需人工处理：1' <<< "$QUEUE"; then
    ATTENTION_READY=1
    break
  fi
  sleep 1
done
if [[ "$ATTENTION_READY" != "1" ]]; then
  cat "$WORK_DIR/server.log"
  cat "$WORK_DIR/plugins/RealTime/data/devices.yml"
  echo "Chunk-load retry or non-wire attention state did not appear on $PROJECT: $QUEUE" >&2
  exit 1
fi

rcon execute if block 1615 64 0 minecraft:stone run say REALTIME_CLEANUP_STONE_PRESERVED >/dev/null || true
grep -Fq 'REALTIME_CLEANUP_STONE_PRESERVED' "$WORK_DIR/server.log" || {
  echo "Cleanup overwrote the non-wire target on $PROJECT: $STONE_CHECK" >&2
  exit 1
}

rcon setblock 1615 64 0 'minecraft:redstone_wire[power=15]' replace >/dev/null
rtime device cleanup retry "$JOB_ID" >/dev/null
EMPTY_READY=0
for _ in $(seq 1 30); do
  QUEUE="$(rtime device cleanup list)"
  if grep -Fq '当前没有待清理设备' <<< "$QUEUE"; then
    EMPTY_READY=1
    break
  fi
  sleep 1
done
if [[ "$EMPTY_READY" != "1" ]]; then
  cat "$WORK_DIR/server.log"
  cat "$WORK_DIR/plugins/RealTime/data/devices.yml"
  echo "Operator retry did not finish the corrected cleanup job on $PROJECT: $QUEUE" >&2
  exit 1
fi

for x in 0 1601 1614 1615; do
  rcon execute if block "$x" 64 0 'minecraft:redstone_wire[power=0]' run say "REALTIME_CLEANUP_POWER_ZERO_$x" >/dev/null || true
  grep -Fq "REALTIME_CLEANUP_POWER_ZERO_$x" "$WORK_DIR/server.log" || {
    echo "Output at x=$x was not cleared to power 0 on $PROJECT." >&2
    exit 1
  }
done

rcon forceload remove 1600 0 >/dev/null
rcon forceload remove 0 0 >/dev/null
echo "$PROJECT cleanup runtime smoke passed: restart recovery, unloaded-chunk deferral, chunk-load retry, NEEDS_ATTENTION preservation, operator retry, and idempotent power-zero cleanup."
