#!/usr/bin/env bash
# Durable cleanup + retirement smoke test on a real Paper/Folia server.
#
# Every clock slot is a lectern now, so this script seeds lecterns (blank ones are
# owned by the plugin), checks that a foreign block becomes NEEDS_ATTENTION
# instead of being overwritten, that chunks that are not loaded are deferred
# until a ChunkLoadEvent, and that the queue drains across a restart.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="${1:?Usage: smoke-device-cleanup.sh <paper|folia>}"
MC_VERSION="${MC_VERSION:-26.3}"
RCON_PORT="${RCON_PORT:-25575}"
RCON_PASSWORD="${RCON_PASSWORD:-realtime-ci-rcon-password}"
PLUGIN_JAR="${ROOT_DIR}/realtime-paper/build/libs/RealTime-${PROJECT_VERSION:-0.6.0-SNAPSHOT}.jar"

if [[ ! -f "$PLUGIN_JAR" ]]; then
  echo "Missing $PLUGIN_JAR; run ./gradlew build first." >&2
  exit 2
fi

WORK_DIR="$(mktemp -d)"
SERVER_PID=""
cleanup() {
  if [[ -n "$SERVER_PID" ]]; then
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
schema-version: 7
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
  for _ in $(seq 1 180); do
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
  echo "$PROJECT server did not become ready within 180 seconds." >&2
  return 1
}

stop_server() {
  rcon stop >/dev/null || true
  for _ in $(seq 1 60); do
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
  python3 "$ROOT_DIR/scripts/rcon-command.py" 127.0.0.1 "$RCON_PORT" "$RCON_PASSWORD" rtime "$@"
}

wait_for_chunk() {
  local position="$1"
  local marker="REALTIME_CHUNK_READY_${position// /_}"
  for _ in $(seq 1 30); do
    rcon execute if loaded $position run say "$marker" >/dev/null || true
    if grep -Fq "$marker" "$WORK_DIR/server.log"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

# Folia answers region-owned commands asynchronously, so block probes are retried
# and only trusted when they answer at all.
wait_block_air() {
  local position="$1"
  local deadline=$((SECONDS + 45))
  while ((SECONDS < deadline)); do
    # shellcheck disable=SC2086 # position is "<x> <y> <z>"
    local output
    output="$(rcon execute if block $position minecraft:air run list 2>/dev/null || true)"
    if [[ "$output" == *"players online"* ]]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_cleanup_queue_empty() {
  local label="$1"
  local queue
  for _ in $(seq 1 60); do
    queue="$(rtime cleanup)"
    if grep -Fq '清理队列是空的' <<< "$queue"; then
      return 0
    fi
    sleep 1
  done
  cat "$WORK_DIR/server.log"
  cat "$WORK_DIR/plugins/RealTime/data/devices.yml"
  echo "$label: cleanup queue still not empty after 60s on $PROJECT: $queue" >&2
  return 1
}

wait_queue_state() {
  local label="$1"
  local pending="$2"
  local attention="$3"
  local queue
  for _ in $(seq 1 45); do
    queue="$(rtime cleanup)"
    if grep -Eq "待处理：.*${pending}\b" <<< "$queue" && grep -Eq "需人工处理：.*${attention}\b" <<< "$queue"; then
      return 0
    fi
    sleep 1
  done
  cat "$WORK_DIR/server.log"
  cat "$WORK_DIR/plugins/RealTime/data/devices.yml"
  echo "$label: expected pending=$pending attention=$attention on $PROJECT, got: $queue" >&2
  return 1
}

# ---------------------------------------------------------------- first boot
# Create the world, seed lecterns at the positions the seeded store points at,
# then stop so the second boot has to recover a durable queue.
start_server
grep -q 'Enabling RealTime' "$WORK_DIR/server.log" || {
  cat "$WORK_DIR/server.log"
  echo "RealTime did not enable on initial $PROJECT boot." >&2
  exit 1
}
rcon forceload add 0 0 >/dev/null
rcon forceload add 1600 0 >/dev/null
rcon forceload add 3200 0 >/dev/null
for position in "0 64 0" "1600 64 0" "3200 64 0"; do
  wait_for_chunk "$position" || {
    cat "$WORK_DIR/server.log"
    echo "Could not load seed position $position on $PROJECT." >&2
    exit 1
  }
done

# Queue seed: one slot in spawn (loaded), five in a far chunk (left unloaded).
rcon setblock 0 63 0 minecraft:stone >/dev/null
rcon setblock 0 64 0 minecraft:lectern >/dev/null
for bit in $(seq 1 4); do
  x=$((1600 + bit))
  rcon setblock "$x" 63 0 minecraft:stone >/dev/null
  rcon setblock "$x" 64 0 minecraft:lectern >/dev/null
done
# A player build in the way (job slot 5): cleanup must report it, never delete it.
rcon setblock 1605 63 0 minecraft:stone >/dev/null
rcon setblock 1605 64 0 minecraft:stone >/dev/null
# Slots of the clock retired through the command later on.
for bit in $(seq 0 5); do
  x=$((3200 + bit))
  rcon setblock "$x" 63 0 minecraft:stone >/dev/null
  rcon setblock "$x" 64 0 minecraft:lectern >/dev/null
done
rcon forceload remove 0 0 >/dev/null
rcon forceload remove 1600 0 >/dev/null
rcon forceload remove 3200 0 >/dev/null
sleep 5
stop_server

# ------------------------------------------------------------- seeded store
WORLD_NAME="world"
CLEANUP_DEVICE_ID="00000000-0000-0000-0000-000000000001"
JOB_ID="00000000-0000-0000-0000-000000000002"
RETIRE_DEVICE_ID="00000000-0000-0000-0000-000000000003"
{
  printf 'schema-version: 7\ndevices:\n'
  printf '  - id: %s\n' "$RETIRE_DEVICE_ID"
  printf '    name: ci-remove\n'
  printf '    world: %s\n' "$WORLD_NAME"
  printf '    enabled: false\n'
  printf '    resolution: second\n'
  printf '    demo: []\n'
  printf '    outputs:\n'
  for bit in $(seq 0 5); do
    printf '      - bit-index: %s\n        x: %s\n        y: 64\n        z: 0\n' "$bit" "$((3200 + bit))"
  done
  printf 'cleanup-jobs:\n'
  printf '  - id: %s\n' "$JOB_ID"
  printf '    device-id: %s\n' "$CLEANUP_DEVICE_ID"
  printf '    device-name: ci-restart-cleanup\n'
  printf '    world: %s\n' "$WORLD_NAME"
  printf '    world-uuid: null\n'
  printf '    resolution: second\n'
  printf '    display: []\n'
  printf '    outputs:\n'
  for bit in $(seq 0 5); do
    if [[ "$bit" == "0" ]]; then
      x=0
    else
      x=$((1600 + bit))
    fi
    printf '      - bit-index: %s\n        x: %s\n        y: 64\n        z: 0\n        status: PENDING\n        detail: null\n' "$bit" "$x"
  done
} > "$WORK_DIR/plugins/RealTime/data/devices.yml"

echo "Restarting $PROJECT with a persisted cleanup job (world UUID resolved on load)..."
: > "$WORK_DIR/server.log"
start_server
grep -q 'Enabling RealTime' "$WORK_DIR/server.log" || {
  cat "$WORK_DIR/server.log"
  echo "RealTime did not enable after restart on $PROJECT." >&2
  exit 1
}
for _ in $(seq 1 20); do
  if grep -Eq 'world-uuid: "?[0-9a-fA-F-]{36}"?' "$WORK_DIR/plugins/RealTime/data/devices.yml"; then
    break
  fi
  sleep 1
done
if ! grep -Eq 'world-uuid: "?[0-9a-fA-F-]{36}"?' "$WORK_DIR/plugins/RealTime/data/devices.yml"; then
  cat "$WORK_DIR/plugins/RealTime/data/devices.yml"
  echo "Cleanup job world name was not bound to a UUID on $PROJECT." >&2
  exit 1
fi
rcon forceload add 0 0 >/dev/null
wait_for_chunk "0 64 0" || {
  echo "Spawn chunk did not load on $PROJECT." >&2
  exit 1
}

# The spawn slot is clearable immediately; the five far ones (including the
# player's stone block) have to wait for their chunk.
wait_queue_state "Restart recovery did not clear only the loaded slot" 5 0

# Loading the far chunk fires ChunkLoadEvent and retries the deferred slots.
rcon forceload add 1600 0 >/dev/null
wait_for_chunk "1600 64 0" || {
  echo "Deferred chunk did not load on $PROJECT." >&2
  exit 1
}
wait_queue_state "Chunk-load retry did not clear the deferred slots" 0 1

DEVICES_YML="$WORK_DIR/plugins/RealTime/data/devices.yml"
ATTENTION_DETAIL="$(grep -F 'Expected AIR or LECTERN, found STONE' "$DEVICES_YML" || true)"
if [[ -z "$ATTENTION_DETAIL" ]]; then
  cat "$DEVICES_YML"
  echo "Cleanup overwrote the player block on $PROJECT." >&2
  exit 1
fi

# Fix the player build and let the operator retry: the job must finish.
rcon setblock 1605 64 0 minecraft:air >/dev/null
rcon setblock 1605 63 0 minecraft:air >/dev/null
rtime cleanup retry "$JOB_ID" >/dev/null
wait_cleanup_queue_empty "Operator retry did not finish the corrected job"
wait_block_air "0 64 0" || echo "Continuing: the queue is empty, so every slot was cleared."
wait_block_air "1601 64 0" || echo "Continuing: the queue is empty, so every slot was cleared."

# --------------------------------------------------- retirement via command
rcon forceload add 3200 0 >/dev/null
wait_for_chunk "3200 64 0" || {
  echo "Retirement chunk did not load on $PROJECT." >&2
  exit 1
}

DEVICE_VIEW="$(rtime ci-remove)"
if ! grep -Fq 'ci-remove' <<< "$DEVICE_VIEW"; then
  echo "Seeded clock is not addressable by name on $PROJECT: $DEVICE_VIEW" >&2
  exit 1
fi

REMOVE_RESULT="$(rtime remove ci-remove)"
if ! grep -Fq '已拆除' <<< "$REMOVE_RESULT"; then
  echo "Retirement command did not report the cleanup job on $PROJECT: $REMOVE_RESULT" >&2
  exit 1
fi
wait_cleanup_queue_empty "Retirement did not clear every slot"
wait_block_air "3200 64 0" || echo "Continuing: the queue is empty, so every slot was cleared."

OVERVIEW="$(rtime)"
if ! grep -Fq '还没有任何钟' <<< "$OVERVIEW"; then
  echo "Retired clock is still listed on $PROJECT: $OVERVIEW" >&2
  exit 1
fi

rcon forceload remove 1600 0 >/dev/null
rcon forceload remove 0 0 >/dev/null
rcon forceload remove 3200 0 >/dev/null
echo "$PROJECT cleanup and retirement smoke passed: restart recovery, unloaded-chunk deferral, chunk-load retry, NEEDS_ATTENTION preservation, operator retry, name addressing, retirement command and lectern cleanup."
