#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
CHANGELOG_PATH="$TEMP_DIR/CHANGELOG.md"

cat > "$CHANGELOG_PATH" <<'CHANGELOG'
# Changelog

## [Unreleased]

- Future work.

## [1.2.3]

- Stable release note.

## [1.3.0-rc.1]

- Prerelease note.
CHANGELOG

export CHANGELOG_PATH

bash "$SCRIPT_DIR/validate-release.sh" "v1.2.3"
bash "$SCRIPT_DIR/validate-release.sh" "v1.3.0-rc.1"

NOTES="$(bash "$SCRIPT_DIR/extract-release-notes.sh" "1.2.3")"
grep -Fq -- "- Stable release note." <<< "$NOTES"

NOTES="$(bash "$SCRIPT_DIR/extract-release-notes.sh" "1.3.0-rc.1")"
grep -Fq -- "- Prerelease note." <<< "$NOTES"

expect_failure() {
  if "$@" >/dev/null 2>&1; then
    echo "ERROR: Expected command to fail: $*" >&2
    exit 1
  fi
}

expect_failure bash "$SCRIPT_DIR/validate-release.sh" "v01.2.3"
expect_failure bash "$SCRIPT_DIR/validate-release.sh" "1.2.3"
expect_failure bash "$SCRIPT_DIR/validate-release.sh" "v9.9.9"

cat > "$TEMP_DIR/empty.md" <<'CHANGELOG'
# Changelog

## [2.0.0]

## [Unreleased]
CHANGELOG
expect_failure env CHANGELOG_PATH="$TEMP_DIR/empty.md" bash "$SCRIPT_DIR/validate-release.sh" "v2.0.0"

echo "Release script tests passed."

# distribution-check.sh must pass on the real tree and fail when the platform
# metadata drifts (missing folia loader, missing folia-supported flag).
bash "$SCRIPT_DIR/distribution-check.sh" >/dev/null

FIXTURE="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR" "$FIXTURE"' EXIT

mkdir -p "$FIXTURE/realtime-paper/src/main/resources" "$FIXTURE/.github/workflows" "$FIXTURE/scripts"
sed 's/^folia-supported: true/folia-supported: false/' \
  "$SCRIPT_DIR/../realtime-paper/src/main/resources/plugin.yml" \
  > "$FIXTURE/realtime-paper/src/main/resources/plugin.yml"
cp "$SCRIPT_DIR/../.github/workflows/release.yml" "$FIXTURE/.github/workflows/release.yml"
cp "$SCRIPT_DIR/_common.sh" "$SCRIPT_DIR/extract-release-notes.sh" "$SCRIPT_DIR/distribution-check.sh" "$FIXTURE/scripts/"
cp "$SCRIPT_DIR/../CHANGELOG.md" "$FIXTURE/CHANGELOG.md"
# _common.sh looks for a Gradle entry point and would otherwise exit 127, which
# would make the negative assertions below pass for the wrong reason.
printf '#!/usr/bin/env bash\nexit 0\n' > "$FIXTURE/gradlew"
chmod +x "$FIXTURE/gradlew"

expect_failure_output() {
  local pattern="$1"; shift
  local out
  if out="$("$@" 2>&1)"; then
    echo "ERROR: Expected command to fail: $*" >&2
    exit 1
  fi
  if ! grep -Fq -- "$pattern" <<< "$out"; then
    echo "ERROR: Expected failure to mention '$pattern', got:" >&2
    echo "$out" >&2
    exit 1
  fi
}

expect_failure_output "folia-supported" bash "$FIXTURE/scripts/distribution-check.sh"

# ...and the Modrinth loader list must name both loaders.
sed -i 's/loaders: \["paper", "folia"\]/loaders: ["paper"]/' "$FIXTURE/.github/workflows/release.yml"
sed -i 's/^folia-supported: false/folia-supported: true/' "$FIXTURE/realtime-paper/src/main/resources/plugin.yml"
expect_failure_output "paper and folia" bash "$FIXTURE/scripts/distribution-check.sh"

echo "Distribution check tests passed."
