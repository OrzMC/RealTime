#!/usr/bin/env bash
# Audit the Hangar and Modrinth project pages against the values we intend to
# publish (see docs/operations/platform-listings.md).
#
# Hangar is fully public, so this runs without credentials. Modrinth is only
# readable with a token that has READ_PROJECT; when the token cannot read the
# project (draft / under review / no scope) the script says so instead of
# pretending the page is fine.
set -euo pipefail
source "$(dirname "$0")/_common.sh"

HANGAR_SLUG="${HANGAR_PROJECT_ID:-RealTime}"
MODRINTH_ID="${MODRINTH_PROJECT_ID:-pRGtDpaD}"
API_VERSION="26.3"
EXPECTED_LICENSE="MIT"
# Hangar's category list is fixed: admin_tools, chat, dev_tools, economy, gameplay,
# games, protection, role_playing, world_management, misc. There is no
# "management", and RealTime is a redstone/technical utility rather than an admin
# tool, so gameplay is the intended one.
EXPECTED_CATEGORY="${EXPECTED_HANGAR_CATEGORY:-gameplay}"
# Hangar keywords are web-only (the project PATCH endpoint is 405), so the audit
# reads them back instead of trusting the doc.
EXPECTED_KEYWORDS="${EXPECTED_HANGAR_KEYWORDS:-redstone,time,clock,utility,folia}"
UA="OrzMC/RealTime platform-audit"
API_HANGAR="https://hangar.papermc.io/api/v1"

problems=0
fail() { echo "FAIL  $*" >&2; problems=$((problems + 1)); }
warn() { echo "warn  $*"; }
pass() { echo "ok    $*"; }
info() { echo "info  $*"; }

echo "== Hangar ($HANGAR_SLUG) =="
hangar="$(curl -fsS --max-time 25 -A "$UA" "https://hangar.papermc.io/api/v1/projects/$HANGAR_SLUG" || true)"
if [[ -z "$hangar" ]]; then
  fail "could not read the Hangar project"
else
  # The long description lives on the project *page*, not in the project JSON:
  # `mainPageContent` on /projects/{slug} stays empty even after the page is set,
  # and the page itself is served as text/plain by /pages/main/{slug}.
  page_len="$(curl -fsS --max-time 25 -A "$UA" "$API_HANGAR/pages/main/$HANGAR_SLUG" 2>/dev/null | wc -c || echo 0)"
  keywords="$(python3 -c 'import json,sys; print(",".join((json.loads(sys.argv[1]).get("settings") or {}).get("keywords") or []))' "$hangar")"
  read -r name category license desc_len links platforms < <(
    python3 - "$hangar" <<'PY'
import json, sys
d = json.loads(sys.argv[1])
s = d.get("settings") or {}
links = (s.get("links") or [{}])[0].get("links") or []
platforms = ",".join(sorted((d.get("supportedPlatforms") or {}).keys())) or "-"
print(
    d.get("name", ""),
    d.get("category", ""),
    (s.get("license") or {}).get("type", ""),
    len(d.get("description") or ""),
    len(links),
    platforms,
)
PY
  )

  [[ "$name" == "RealTime" ]] && pass "name: $name" || fail "name is '$name', expected RealTime"
  [[ "$category" == "$EXPECTED_CATEGORY" ]] && pass "category: $category" ||
    fail "category is '$category', expected '$EXPECTED_CATEGORY'"
  [[ "$license" == "$EXPECTED_LICENSE" ]] && pass "license: $license" ||
    fail "license is '$license', expected '$EXPECTED_LICENSE'"
  [[ "$desc_len" -gt 20 ]] && pass "short description set ($desc_len chars)" ||
    fail "short description missing ($desc_len chars)"
  [[ "$page_len" -gt 200 ]] && pass "main page set ($page_len chars)" ||
    fail "main page missing ($page_len chars) — run scripts/hangar-page.sh (or paste the markdown from platform-listings.md)"
  [[ "$links" -ge 1 ]] && pass "links: $links entry set(s)" || fail "no links configured"
  if [[ -n "$EXPECTED_KEYWORDS" ]]; then
    if [[ "$keywords" == "$EXPECTED_KEYWORDS" ]]; then
      pass "keywords: $keywords"
    else
      fail "keywords are '$keywords', expected '$EXPECTED_KEYWORDS' (Hangar has no keyword API: fix it in the web UI)"
    fi
  fi
  case ",$platforms," in
    *",PAPER,"*) pass "platforms: $platforms" ;;
    *) fail "platforms is '$platforms', expected PAPER" ;;
  esac

  versions="$(curl -fsS --max-time 25 -A "$UA" "https://hangar.papermc.io/api/v1/projects/$HANGAR_SLUG/versions" || true)"
  if [[ -z "$versions" ]]; then
    warn "could not read the Hangar version list"
  else
    python3 - "$versions" <<'PY' || problems=$((problems + 1))
import json, sys
d = json.loads(sys.argv[1])
rows = d.get("result") or []
if not rows:
    print("FAIL  no versions published on Hangar")
    raise SystemExit(1)
bad = 0
for v in rows:
    channel = (v.get("channel") or {}).get("name")
    deps = (v.get("platformDependencies") or {}).get("PAPER") or []
    files = v.get("downloads") or {}
    info = ((files.get("PAPER") or {}).get("fileInfo")) or {}
    name = v.get("name")
    line = f"{name}: channel={channel} paper={','.join(deps)} size={info.get('sizeBytes')} sha256={(info.get('sha256Hash') or '')[:12]}…"
    if channel not in ("Release", "Snapshot"):
        print(f"FAIL  {line} (unexpected channel)")
        bad += 1
    elif "26.3" not in deps:
        print(f"FAIL  {line} (does not declare 26.3)")
        bad += 1
    elif not info.get("sha256Hash"):
        print(f"FAIL  {line} (no checksum)")
        bad += 1
    else:
        print(f"ok    {line}")
raise SystemExit(1 if bad else 0)
PY
  fi
fi

echo
echo "== Modrinth ($MODRINTH_ID) =="
if [[ -z "${MODRINTH_TOKEN:-}" ]]; then
  info "MODRINTH_TOKEN unset: cannot read a draft/review project; set it and re-run"
else
  code="$(curl -s -o /tmp/rt-audit-modrinth.json -w '%{http_code}' --max-time 25 -A "$UA" \
    -H "Authorization: $MODRINTH_TOKEN" "https://api.modrinth.com/v2/project/$MODRINTH_ID" || true)"
  if [[ "$code" != "200" ]]; then
    info "token cannot read the project (HTTP $code) — Modrinth hides draft/review projects and requires READ_PROJECT"
  else
    python3 - /tmp/rt-audit-modrinth.json "$EXPECTED_LICENSE" "$API_VERSION" <<'PY' || problems=$((problems + 1))
import json, sys
d = json.load(open(sys.argv[1]))
expected_license, api_version = sys.argv[2], sys.argv[3]
bad = 0
def check(ok, good, badmsg):
    global bad
    print(("ok    " if ok else "FAIL  ") + (good if ok else badmsg))
    if not ok:
        bad += 1
check(d.get("slug") and d.get("id"), f"id={d.get('id')} slug={d.get('slug')} status={d.get('status')}",
      "project has no id/slug")
check(len(d.get("description") or "") > 20, f"summary set ({len(d.get('description') or '')} chars)", "summary missing")
check(len(d.get("body") or "") > 200, f"long description set ({len(d.get('body') or '')} chars)", "long description missing")
check(bool(d.get("categories")), f"categories: {d.get('categories')}", "no categories")
check((d.get("license") or {}).get("id", "").lower() == expected_license.lower(),
      f"license: {(d.get('license') or {}).get('id')}", f"license is {(d.get('license') or {}).get('id')}, expected {expected_license}")
check(bool(d.get("source_url")), f"source: {d.get('source_url')}", "source_url missing")
check(api_version in (d.get("game_versions") or []), f"game versions: {d.get('game_versions')}",
      f"game versions {d.get('game_versions')} do not include {api_version}")
check({"paper", "folia"} <= set(d.get("loaders") or []), f"loaders: {d.get('loaders')}", "loaders must include paper and folia")
raise SystemExit(1 if bad else 0)
PY
  fi
fi

rm -f /tmp/rt-audit-modrinth.json
echo
if [[ "$problems" -gt 0 ]]; then
  echo "platform audit found $problems problem(s)" >&2
  exit 1
fi
echo "platform audit passed"
