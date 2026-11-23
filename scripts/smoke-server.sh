#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="${1:?Usage: smoke-server.sh <paper|folia>}"
MC_VERSION="${MC_VERSION:-26.3}"
RCON_PORT="${RCON_PORT:-25575}"
RCON_PASSWORD="${RCON_PASSWORD:-realtime-ci-rcon-password}"
WITH_PLACEHOLDERAPI="${WITH_PLACEHOLDERAPI:-false}"

if [[ "$PROJECT" != "paper" && "$PROJECT" != "folia" ]]; then
  echo "Unsupported server project: $PROJECT" >&2
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

echo "Downloading server artifact..."
curl --fail --location --silent --show-error --connect-timeout 15 --max-time 180 "$DOWNLOAD_URL" -o "$WORK_DIR/server.jar"
echo "$SHA256  $WORK_DIR/server.jar" | sha256sum --check -
echo "Server artifact checksum verified."

mkdir -p "$WORK_DIR/plugins"
cp "$ROOT_DIR/realtime-paper/build/libs/RealTime-${PROJECT_VERSION:-0.1.0-SNAPSHOT}.jar" "$WORK_DIR/plugins/RealTime.jar"
if [[ "$WITH_PLACEHOLDERAPI" == "true" ]]; then
  bash "$ROOT_DIR/scripts/download-placeholderapi.sh" "$WORK_DIR/plugins"
fi
printf 'eula=true\n' > "$WORK_DIR/eula.txt"
cat > "$WORK_DIR/server.properties" <<PROPERTIES
server-port=25566
online-mode=false
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASSWORD
broadcast-rcon-to-ops=false
view-distance=3
simulation-distance=3
PROPERTIES

(
  cd "$WORK_DIR"
  java -Xms1G -Xmx2G -jar server.jar --nogui
) > "$WORK_DIR/server.log" 2>&1 &
SERVER_PID=$!
echo "Waiting for $PROJECT server startup..."

READY=0
for _ in $(seq 1 120); do
  if grep -q 'Done (' "$WORK_DIR/server.log"; then
    READY=1
    break
  fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    cat "$WORK_DIR/server.log"
    echo "$PROJECT server exited before startup completed." >&2
    exit 1
  fi
  sleep 1
done

if [[ "$READY" != "1" ]]; then
  cat "$WORK_DIR/server.log"
  echo "$PROJECT server did not become ready within 120 seconds." >&2
  exit 1
fi

grep -q 'Enabling RealTime' "$WORK_DIR/server.log" || {
  cat "$WORK_DIR/server.log"
  echo "RealTime did not enable on $PROJECT." >&2
  exit 1
}

SCHEDULER_READY=0
for _ in $(seq 1 20); do
  if grep -q 'global scheduler adapter ready' "$WORK_DIR/server.log"; then
    SCHEDULER_READY=1
    break
  fi
  sleep 1
done
if [[ "$SCHEDULER_READY" != "1" ]]; then
  cat "$WORK_DIR/server.log"
  echo "Global scheduler adapter did not execute on $PROJECT." >&2
  exit 1
fi

python3 "$ROOT_DIR/scripts/rcon-command.py" 127.0.0.1 "$RCON_PORT" "$RCON_PASSWORD" rtime
python3 "$ROOT_DIR/scripts/rcon-command.py" 127.0.0.1 "$RCON_PORT" "$RCON_PASSWORD" rtime info
python3 "$ROOT_DIR/scripts/rcon-command.py" 127.0.0.1 "$RCON_PORT" "$RCON_PASSWORD" rtime reload

if [[ "$WITH_PLACEHOLDERAPI" == "true" ]]; then
  PAPI_READY=0
  for _ in $(seq 1 20); do
    if grep -q 'PlaceholderAPI expansion registered' "$WORK_DIR/server.log"; then
      PAPI_READY=1
      break
    fi
    sleep 1
  done
  if [[ "$PAPI_READY" != "1" ]]; then
    cat "$WORK_DIR/server.log"
    echo "RealTime PlaceholderAPI expansion did not register on $PROJECT." >&2
    exit 1
  fi

  PAPI_TIME_OUTPUT="$(
    python3 "$ROOT_DIR/scripts/rcon-command.py" 127.0.0.1 "$RCON_PORT" "$RCON_PASSWORD" \
      --no-expect "papi parse --null %realtime_time%"
  )"
  if ! grep -Eq '20[0-9]{2}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}' <<< "$PAPI_TIME_OUTPUT"; then
    echo "PlaceholderAPI time placeholder did not resolve: $PAPI_TIME_OUTPUT" >&2
    exit 1
  fi

  PAPI_BCD_OUTPUT="$(
    python3 "$ROOT_DIR/scripts/rcon-command.py" 127.0.0.1 "$RCON_PORT" "$RCON_PASSWORD" \
      --no-expect "papi parse --null %realtime_bcd%"
  )"
  if ! grep -Eq '(^|[^0-9])[0-2][0-9][0-5][0-9]([^0-9]|$)' <<< "$PAPI_BCD_OUTPUT"; then
    echo "PlaceholderAPI BCD placeholder did not resolve: $PAPI_BCD_OUTPUT" >&2
    exit 1
  fi
  echo "PlaceholderAPI expansion and null-player parsing passed on $PROJECT."
fi

echo "$PROJECT $MC_VERSION startup, global scheduler, /rtime, info, reload, and optional PlaceholderAPI smoke tests passed."
