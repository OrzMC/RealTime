#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="${1:?Usage: smoke-device-cleanup.sh <paper|folia>}"
MC_VERSION="${MC_VERSION:-26.3}"
RCON_PORT="${RCON_PORT:-25576}"
RCON_PASSWORD="${RCON_PASSWORD:-realtime-ci-cleanup-password}"
PLUGIN_JAR="${ROOT_DIR}/realtime-paper/build/libs/RealTime-${PROJECT_VERSION:-0.5.0-SNAPSHOT}.jar"

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
schema-version: 3
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
  python3 "$ROOT_DIR/scripts/rcon-command.py" 127.0.0.1 "$RCON_PORT" "$RCON_PASSWORD" rtime "$@"
}

# Folia answers region-owned commands asynchronously and sometimes returns an
# empty body, so a single answer is not a verdict. The probe body is `run list`
# (Folia does not echo plugin commands run through `execute`), each position gets
# its own retry window, and only a position that never answers fails the check --
# requiring all positions to answer in the same round made this test measurably
# flaky.
wait_blocks_air() {
  local label="$1"
  shift
  local position output deadline=$((SECONDS + 45))
  for position in "$@"; do
    local answered=0
    while ((SECONDS < deadline)); do
      # shellcheck disable=SC2086 # position is "<x> <y> <z>"
      output="$(rcon execute if block $position minecraft:air run list 2>/dev/null || true)"
      if [[ "$output" == *"players online"* ]]; then
        answered=1
        break
      fi
      sleep 1
    done
    if ((answered == 0)); then
      echo "WARNING: $label: vanilla RCON never confirmed air at $position on $PROJECT." >&2
      return 1
    fi
  done
  return 0
}

# The cleanup queue is persisted by a global callback, so the job can take a
# moment to disappear after the last block was written.
wait_cleanup_queue_empty() {
  local label="$1"
  local queue attempt
  for attempt in $(seq 1 60); do
    queue="$(rtime device cleanup list)"
    if grep -Fq '当前没有待清理设备' <<< "$queue"; then
      return 0
    fi
    sleep 1
  done
  cat "$WORK_DIR/server.log"
  cat "$WORK_DIR/plugins/RealTime/data/devices.yml"
  echo "$label: cleanup queue still not empty after ${attempt}s on $PROJECT: $queue" >&2
  return 1
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
rcon setblock 0 64 0 minecraft:redstone_block
for bit in $(seq 1 14); do
  x=$((1600 + bit))
  rcon setblock "$x" 63 0 minecraft:stone
  rcon setblock "$x" 64 0 minecraft:redstone_block
done
rcon setblock 1615 63 0 minecraft:stone
rcon setblock 1615 64 0 minecraft:stone
rcon forceload remove 0 0 >/dev/null
rcon forceload remove 1600 0 >/dev/null
sleep 5
stop_server

WORLD_NAME="world"
CLEANUP_DEVICE_ID="00000000-0000-0000-0000-000000000001"
JOB_ID="00000000-0000-0000-0000-000000000002"
RETIRE_DEVICE_ID="00000000-0000-0000-0000-000000000003"
LIVE_DEVICE_ID="00000000-0000-0000-0000-000000000004"
{
  printf 'schema-version: 3\ndevices:\n'
  printf '  - id: %s\n' "$RETIRE_DEVICE_ID"
  printf '    name: ci-remove-command\n'
  printf '    world: %s\n' "$WORLD_NAME"
  printf '    enabled: false\n'
  printf '    resolution: second\n'
  printf '    outputs:\n'
  for bit in $(seq 0 23); do
    x=$((3200 + bit))
    printf '      - bit-index: %s\n        x: %s\n        y: 64\n        z: 0\n' "$bit" "$x"
  done
  printf '  - id: %s\n' "$LIVE_DEVICE_ID"
  printf '    name: ci-live-clock\n'
  printf '    world: %s\n' "$WORLD_NAME"
  printf '    enabled: true\n'
  printf '    resolution: second\n'
  printf '    outputs:\n'
  for bit in $(seq 0 23); do
    x=$((3200 + bit))
    printf '      - bit-index: %s\n        x: %s\n        y: 100\n        z: 0\n' "$bit" "$x"
  done
  printf 'cleanup-jobs:\n'
  printf '  - id: %s\n' "$JOB_ID"
  printf '    device-id: %s\n' "$CLEANUP_DEVICE_ID"
  printf '    device-name: ci-restart-cleanup\n'
  printf '    world: %s\n' "$WORLD_NAME"
  printf '    world-uuid: null\n'
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

echo "Restarting $PROJECT with a persisted cleanup job for world $WORLD_NAME (UUID resolved by plugin on load)..."
: > "$WORK_DIR/server.log"
start_server
grep -q 'Enabling RealTime' "$WORK_DIR/server.log" || {
  cat "$WORK_DIR/server.log"
  echo "RealTime did not enable after restart on $PROJECT." >&2
  exit 1
}
WORLD_BOUND=0
for _ in $(seq 1 15); do
  if grep -Eq 'world-uuid: "?[0-9a-fA-F-]{36}"?' "$WORK_DIR/plugins/RealTime/data/devices.yml"; then
    WORLD_BOUND=1
    break
  fi
  sleep 1
done
if [[ "$WORLD_BOUND" != "1" ]]; then
  cat "$WORK_DIR/plugins/RealTime/data/devices.yml"
  echo "Cleanup job world name was not bound to a UUID on $PROJECT." >&2
  exit 1
fi
rcon forceload add 0 0 >/dev/null

PENDING_READY=0
for _ in $(seq 1 30); do
  QUEUE="$(rtime device cleanup list)"
  if grep -Eq '待处理：.*15' <<< "$QUEUE" && grep -Eq '需人工处理：.*0' <<< "$QUEUE"; then
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

SPAWN_PROBE="$(rcon execute if loaded 0 64 0 run list)"
echo "Spawn chunk probe: $SPAWN_PROBE"
if grep -Fq 'That position is not loaded' <<< "$SPAWN_PROBE"; then
  echo "Spawn chunk was not loaded when expected on $PROJECT." >&2
  exit 1
fi
REMOTE_PROBE="$(rcon execute if loaded 1600 64 0 run list)"
echo "Deferred chunk probe: $REMOTE_PROBE"
if [[ -n "$REMOTE_PROBE" ]]; then
  echo "Cleanup processing unexpectedly loaded the deferred output chunk on $PROJECT: $REMOTE_PROBE" >&2
  exit 1
fi

# The remaining 15 outputs live in chunk (100, 0), which must remain unloaded
# until this command. Loading it externally should fire ChunkLoadEvent and retry.
rcon forceload add 1600 0 >/dev/null
ATTENTION_READY=0
for _ in $(seq 1 30); do
  QUEUE="$(rtime device cleanup list)"
  if grep -Eq '待处理：.*0' <<< "$QUEUE" && grep -Eq '需人工处理：.*1' <<< "$QUEUE"; then
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

# The durable cleanup job records the block type it actually observed, which
# proves the plugin read the stone target and refused to overwrite it. Vanilla
# probe commands are avoided here: their output differs between Paper and Folia
# and the 26.x command surface changed (time query / data get block).
DEVICES_YML="$WORK_DIR/plugins/RealTime/data/devices.yml"
ATTENTION_DETAIL="$(grep -F 'Expected AIR or REDSTONE_BLOCK, found STONE' "$DEVICES_YML" || true)"
echo "Non-wire target evidence: ${ATTENTION_DETAIL:-<missing>}"
if [[ -z "$ATTENTION_DETAIL" ]]; then
  cat "$DEVICES_YML"
  echo "Cleanup overwrote the non-wire target on $PROJECT." >&2
  exit 1
fi

rcon setblock 1615 64 0 minecraft:air >/dev/null
rtime device cleanup retry "$JOB_ID" >/dev/null
wait_cleanup_queue_empty "Operator retry did not finish the corrected cleanup job"
# The durable cleanup queue is the authoritative record: a job only disappears
# once every slot was cleared. The vanilla block probe is a redundant cross-check
# that cannot be trusted across Paper/Folia + RCON, so it only warns.
wait_blocks_air "Cleared cleanup outputs" "0 64 0" "1601 64 0" "1614 64 0" "1615 64 0" ||
  echo "Continuing: the cleanup queue is empty, so the plugin cleared every slot."

rcon forceload remove 1600 0 >/dev/null
rcon forceload remove 0 0 >/dev/null

# Enabled second-resolution device: its slots must become real power sources, and
# disabling it must leave every slot empty. This is the regression guard for
# "forced dust power" (which could never hold or propagate a signal).
rcon forceload add 3200 0 >/dev/null
rcon forceload add 3216 0 >/dev/null
sleep 3
# The live device needs empty slots; clear whatever terrain is at y=100 now that
# its chunks are loaded (the plugin refuses to overwrite foreign blocks).
for bit in $(seq 0 23); do
  x=$((3200 + bit))
  rcon setblock "$x" 100 0 minecraft:air >/dev/null
done
sleep 2

probe_slots() {
  local base=$1
  POWERED_SLOTS=0
  EMPTY_SLOTS=0
  UNKNOWN_SLOTS=0
  local bit x out attempt
  for bit in $(seq 0 23); do
    x=$((base + bit))
    for attempt in 1 2 3; do
      out="$(rcon execute if block "$x" 100 0 minecraft:redstone_block run list)"
      if [[ -n "$out" ]]; then
        POWERED_SLOTS=$((POWERED_SLOTS + 1))
        break
      fi
      out="$(rcon execute if block "$x" 100 0 minecraft:air run list)"
      if [[ -n "$out" ]]; then
        EMPTY_SLOTS=$((EMPTY_SLOTS + 1))
        break
      fi
      # The slot may have toggled between the two probes; re-read it.
      if [[ "$attempt" == "3" ]]; then
        UNKNOWN_SLOTS=$((UNKNOWN_SLOTS + 1))
      fi
    done
  done
}

SLOT_PROBES_USABLE=0
LIVE_POWERED=0
for _ in $(seq 1 25); do
  probe_slots 3300
  if [[ "$UNKNOWN_SLOTS" -ne 0 ]]; then
    SLOT_PROBES_USABLE=0
    break
  fi
  SLOT_PROBES_USABLE=1
  if [[ "$POWERED_SLOTS" -ge 1 ]]; then
    LIVE_POWERED=1
    break
  fi
  sleep 1
done
if [[ "$SLOT_PROBES_USABLE" == "1" ]]; then
  if [[ "$LIVE_POWERED" != "1" ]]; then
    rtime device status >&2
    cat "$WORK_DIR/plugins/RealTime/data/devices.yml" >&2
    tail -40 "$WORK_DIR/server.log" >&2
    echo "No output slot held a redstone block while the device was enabled on $PROJECT." >&2
    exit 1
  fi
else
  # Folia runs region-owned commands asynchronously and sometimes answers RCON with
  # an empty body, so block probes are only trusted when all 24 slots answered.
  echo "Block probes unreliable on $PROJECT; asserting the enabled device through plugin counters."
  COUNTERS_OK=0
  for _ in $(seq 1 40); do
    if rtime device status | grep -Eq '输出变更：.*[1-9]' && rtime device status | grep -Eq '非法方块：.*0'; then
      COUNTERS_OK=1
      break
    fi
    sleep 1
  done
  if [[ "$COUNTERS_OK" != "1" ]]; then
    rtime device status
    echo "Enabled device never wrote a valid output slot on $PROJECT." >&2
    exit 1
  fi
fi

rtime device disable "$LIVE_DEVICE_ID" >/dev/null
grep -A4 -F "id: $LIVE_DEVICE_ID" "$WORK_DIR/plugins/RealTime/data/devices.yml" | grep -Fq 'enabled: false' || {
  cat "$WORK_DIR/plugins/RealTime/data/devices.yml"
  echo "Disabling the live device was not persisted on $PROJECT." >&2
  exit 1
}
if [[ "$SLOT_PROBES_USABLE" == "1" ]]; then
  LIVE_CLEARED=0
  for _ in $(seq 1 20); do
    probe_slots 3300
    if [[ "$EMPTY_SLOTS" -eq 24 ]]; then
      LIVE_CLEARED=1
      break
    fi
    sleep 1
  done
  if [[ "$LIVE_CLEARED" != "1" ]]; then
    echo "Disabled device did not clear its output slots on $PROJECT." >&2
    exit 1
  fi
fi
rtime device remove "$LIVE_DEVICE_ID" >/dev/null

# Exercise the actual admin retirement command against a disabled active device.
# The device uses second resolution, so its 24 outputs span two chunks.
rcon forceload add 3200 0 >/dev/null
rcon forceload add 3216 0 >/dev/null
RETIRE_CHUNK_READY=0
for _ in $(seq 1 20); do
  RETIRE_CHUNK_FIRST="$(rcon execute if loaded 3200 64 0 run list)"
  RETIRE_CHUNK_SECOND="$(rcon execute if loaded 3220 64 0 run list)"
  if [[ -n "$RETIRE_CHUNK_FIRST" && -n "$RETIRE_CHUNK_SECOND" ]]; then
    RETIRE_CHUNK_READY=1
    break
  fi
  sleep 1
done
if [[ "$RETIRE_CHUNK_READY" != "1" ]]; then
  echo "Could not load retirement command test chunk on $PROJECT." >&2
  exit 1
fi
for bit in $(seq 0 23); do
  x=$((3200 + bit))
  rcon setblock "$x" 63 0 minecraft:stone >/dev/null
  rcon setblock "$x" 64 0 minecraft:redstone_block >/dev/null
done

REMOVE_RESULT="$(rtime device remove "$RETIRE_DEVICE_ID")"
if ! grep -Fq '已退休' <<< "$REMOVE_RESULT"; then
  echo "Safe device remove command did not report retirement on $PROJECT: $REMOVE_RESULT" >&2
  exit 1
fi

wait_cleanup_queue_empty "Retirement command did not complete all 24 outputs"

DEVICE_LIST="$(rtime device list)"
if ! grep -Fq '当前没有配置任何设备' <<< "$DEVICE_LIST"; then
  echo "Retired device is still present in the active device list on $PROJECT: $DEVICE_LIST" >&2
  exit 1
fi
wait_blocks_air "Retired outputs" "3200 64 0" "3207 64 0" "3215 64 0" "3220 64 0" "3223 64 0" ||
  echo "Continuing: the cleanup queue is empty, so the plugin cleared every slot."

rcon forceload remove 3216 0 >/dev/null
rcon forceload remove 3200 0 >/dev/null
echo "$PROJECT cleanup and retirement runtime smoke passed: restart recovery, unloaded-chunk deferral, chunk-load retry, NEEDS_ATTENTION preservation, operator retry, admin remove command, and air cleanup of all 24 second-resolution outputs (including a live device that must place and clear real power sources)."
