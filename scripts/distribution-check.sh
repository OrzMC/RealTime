#!/usr/bin/env bash
# Distribution readiness check for Hangar and Modrinth.
#
# Offline (default): verifies the local metadata and workflow wiring that the
# publishing steps depend on, then prints the exact payloads that would be sent.
# Remote (--remote): additionally asks the public APIs whether the configured
# project, game version and loaders exist.
#
# Credentials are never required: the tokens only live in the protected GitHub
# `release` environment, and this script refuses to read them from files.
set -euo pipefail
source "$(dirname "$0")/_common.sh"

REMOTE=0
CHECK_TOKENS=0
VERSION=""

usage() {
  cat <<'EOF'
Usage: scripts/distribution-check.sh [--remote] [--version X.Y.Z]

  --remote         also query the public Hangar/Modrinth APIs
  --check-tokens   also validate HANGAR_API_TOKEN / MODRINTH_TOKEN from the environment
                   (never prints them; intended for verifying a freshly created key)
  --version VER    version shown in the Modrinth payload preview (default: next snapshot)
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --remote) REMOTE=1; shift ;;
    --check-tokens) CHECK_TOKENS=1; shift ;;
    --version) VERSION="${2:?--version needs a value}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

PLUGIN_YML="$ROOT_DIR/realtime-paper/src/main/resources/plugin.yml"
WORKFLOW="$ROOT_DIR/.github/workflows/release.yml"
API_VERSION="26.3"
failures=0

fail() { echo "FAIL  $*" >&2; failures=$((failures + 1)); }
pass() { echo "ok    $*"; }
info() { echo "info  $*"; }

require_pattern() {
  local file="$1" pattern="$2" label="$3"
  if grep -qE "$pattern" "$file"; then pass "$label"; else fail "$label"; fi
}

echo "== Platform metadata =="
require_pattern "$PLUGIN_YML" '^api-version: *.(26\.3).' "plugin.yml declares api-version $API_VERSION"
require_pattern "$PLUGIN_YML" '^folia-supported: *true' "plugin.yml declares folia-supported: true (Hangar and Modrinth both rely on it)"
require_pattern "$PLUGIN_YML" '^main: ' "plugin.yml declares the entry point"

echo
echo "== Release workflow wiring =="
require_pattern "$WORKFLOW" 'HANGAR_PROJECT_ID' "workflow reads the Hangar project id from repository variables"
require_pattern "$WORKFLOW" 'HANGAR_API_TOKEN' "workflow reads the Hangar token from environment secrets"
require_pattern "$WORKFLOW" 'MODRINTH_PROJECT_ID' "workflow reads the Modrinth project id from repository variables"
require_pattern "$WORKFLOW" 'MODRINTH_TOKEN' "workflow reads the Modrinth token from environment secrets"
require_pattern "$WORKFLOW" 'loaders: *\[.*paper.*folia.*\]|loaders: *\[.*folia.*paper.*\]' \
  "Modrinth payload lists both paper and folia loaders"
require_pattern "$WORKFLOW" "game_versions: *\[\"$API_VERSION\"\]" "Modrinth payload pins game version $API_VERSION"
require_pattern "$WORKFLOW" 'publishPluginPublicationToHangar' "workflow invokes the Hangar publication task"
require_pattern "$WORKFLOW" 'skip.*Hangar|skipping Hangar' "missing Hangar credentials skip instead of failing"
require_pattern "$WORKFLOW" 'skipping Modrinth' "missing Modrinth credentials skip instead of failing"

echo
echo "== Configuration presence (report only) =="
info "HANGAR_PROJECT_ID=${HANGAR_PROJECT_ID:-<unset>}"
info "MODRINTH_PROJECT_ID=${MODRINTH_PROJECT_ID:-<unset>}"
if [[ -n "${HANGAR_API_TOKEN:-}" ]]; then info "HANGAR_API_TOKEN=<set>"; else info "HANGAR_API_TOKEN=<unset>"; fi
if [[ -n "${MODRINTH_TOKEN:-}" ]]; then info "MODRINTH_TOKEN=<set>"; else info "MODRINTH_TOKEN=<unset>"; fi
info "以上三项由 GitHub release 环境的仓库变量/密钥提供，本脚本不需要它们"

echo
echo "== Modrinth payload preview =="
if [[ -z "$VERSION" ]]; then
  # Keep the preview aligned with the development version without hard-coding it.
  VERSION="$(grep -oE 'orElse\("[0-9]+\.[0-9]+\.[0-9]+' "$ROOT_DIR/build.gradle.kts" | head -1 | sed 's/.*"//')"
  VERSION="${VERSION:-0.0.0}"
fi
PREVIEW_VERSION="$VERSION"
CHANGELOG_PREVIEW="$(bash "$ROOT_DIR/scripts/extract-release-notes.sh" 0.4.0 2>/dev/null | head -n 20 || echo '(no changelog section found)')"
jq -cn \
  --arg name "RealTime $PREVIEW_VERSION" \
  --arg version "$PREVIEW_VERSION" \
  --arg changelog "$CHANGELOG_PREVIEW" \
  --arg type "$(case "$PREVIEW_VERSION" in *-*) echo beta ;; *) echo release ;; esac)" \
  --arg game "$API_VERSION" \
  '{
    name: $name,
    version_number: $version,
    game_versions: [$game],
    loaders: ["paper", "folia"],
    version_type: $type,
    featured: false,
    status: "listed",
    file_parts: ["plugin"],
    primary_file: "plugin",
    changelog_bytes: ($changelog | length)
  }'

echo
echo "== Hangar target =="
info "platform: PAPER only — hangar-publish-plugin 0.1.4 exposes PAPER/WATERFALL/VELOCITY"
info "Folia support is advertised through plugin.yml folia-supported: true, not a separate Hangar platform"
info "channel: Release for stable tags, Snapshot for prereleases (prereleases are not uploaded)"

if [[ "$REMOTE" -eq 1 ]]; then
  echo
  echo "== Remote checks =="
  ua="OrzMC/RealTime distribution-check"

  game_versions="$(curl -fsS --max-time 20 -A "$ua" https://api.modrinth.com/v2/tag/game_version || true)"
  if [[ -z "$game_versions" ]]; then
    fail "could not fetch the Modrinth game version list"
  elif jq -e --arg v "$API_VERSION" 'any(.[]; .version == $v)' <<<"$game_versions" >/dev/null; then
    pass "Modrinth knows game version $API_VERSION"
  else
    fail "Modrinth does not list game version $API_VERSION"
  fi

  loaders="$(curl -fsS --max-time 20 -A "$ua" https://api.modrinth.com/v2/tag/loader || true)"
  if [[ -z "$loaders" ]]; then
    fail "could not fetch the Modrinth loader list"
  else
    for loader in paper folia; do
      if jq -e --arg l "$loader" 'any(.[]; .name == $l)' <<<"$loaders" >/dev/null; then
        pass "Modrinth knows loader $loader"
      else
        fail "Modrinth does not list loader $loader"
      fi
    done
  fi

  if [[ -n "${MODRINTH_PROJECT_ID:-}" ]]; then
    if curl -fsS --max-time 20 -A "$ua" "https://api.modrinth.com/v2/project/$MODRINTH_PROJECT_ID" >/dev/null; then
      pass "Modrinth project $MODRINTH_PROJECT_ID exists"
    else
      fail "Modrinth project $MODRINTH_PROJECT_ID is not visible (wrong slug/id or not published)"
    fi
  else
    info "MODRINTH_PROJECT_ID unset; skipping project lookup"
  fi

  if [[ "$CHECK_TOKENS" -eq 1 ]]; then
    ua_tokens="OrzMC/RealTime distribution-check"
    if [[ -n "${HANGAR_API_TOKEN:-}" ]]; then
      # Hangar's identity endpoint takes the key as the `apiKey` query parameter
      # (the Authorization header is rejected there) and answers 200 for a valid
      # key, 400 for a missing/invalid one. The body is a JWT session token, so
      # it is never read: only the status code is inspected.
      code="$(curl -s -X POST -o /dev/null -w '%{http_code}' --max-time 20 \
        -A "$ua_tokens" --get --data-urlencode "apiKey=$HANGAR_API_TOKEN" \
        https://hangar.papermc.io/api/v1/authenticate || true)"
      if [[ "$code" == "200" ]]; then
        pass "HANGAR_API_TOKEN is accepted by Hangar"
      else
        fail "HANGAR_API_TOKEN was rejected by Hangar (HTTP ${code:-none}, expected 200); recreate the key"
      fi
    else
      info "HANGAR_API_TOKEN unset; skipping token check"
    fi
    if [[ -n "${MODRINTH_TOKEN:-}" ]]; then
      # Modrinth answers 200 for a valid PAT and 401 for a missing/invalid one.
      code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 \
        -A "$ua_tokens" -H "Authorization: $MODRINTH_TOKEN" \
        https://api.modrinth.com/v2/user || true)"
      if [[ "$code" == "200" ]]; then
        pass "MODRINTH_TOKEN is accepted by Modrinth"
      else
        fail "MODRINTH_TOKEN was rejected by Modrinth (HTTP ${code:-none}); recreate the PAT"
      fi
    else
      info "MODRINTH_TOKEN unset; skipping token check"
    fi
  fi

  if [[ -n "${HANGAR_PROJECT_ID:-}" ]]; then
    if curl -fsS --max-time 20 -A "$ua" "https://hangar.papermc.io/api/v1/projects/$HANGAR_PROJECT_ID" >/dev/null; then
      pass "Hangar project $HANGAR_PROJECT_ID exists"
    else
      fail "Hangar project $HANGAR_PROJECT_ID is not visible (use the project slug, not the display name)"
    fi
  else
    info "HANGAR_PROJECT_ID unset; skipping project lookup"
  fi
fi

echo
if [[ "$failures" -gt 0 ]]; then
  echo "distribution check failed: $failures problem(s)" >&2
  exit 1
fi
echo "distribution check passed"
