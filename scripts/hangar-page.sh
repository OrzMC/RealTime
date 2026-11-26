#!/usr/bin/env bash
# Push the shared long description from docs/operations/platform-listings.md to
# the Hangar project's main page.
#
# Hangar exposes PATCH /api/v1/pages/editmain/{project} for this (permission
# `edit_page` on the project), so the page can be kept in sync from the repo
# instead of being pasted by hand. Project settings - category, licence, links -
# have no API and stay manual.
set -euo pipefail
source "$(dirname "$0")/_common.sh"

SLUG="${HANGAR_PROJECT_ID:-RealTime}"
DOC="$ROOT_DIR/docs/operations/platform-listings.md"
API="https://hangar.papermc.io/api/v1"
UA="OrzMC/RealTime hangar-page"

if [[ -z "${HANGAR_API_TOKEN:-}" ]]; then
  echo "HANGAR_API_TOKEN is required (create it at https://hangar.papermc.io/auth/settings/api-keys with edit_page)." >&2
  exit 2
fi

payload="$(python3 - "$DOC" <<'PY'
import json, re, sys
doc = open(sys.argv[1], encoding="utf-8").read()
match = re.search(r"````markdown\n(.*?)\n````", doc, re.S)
if not match:
    sys.exit("no ```markdown block found in " + sys.argv[1])
print(json.dumps({"content": match.group(1)}))
PY
)"

# Hangar API keys are not accepted as bearer tokens on ordinary endpoints: they
# have to be exchanged for a short-lived JWT at /authenticate first. (A rejected
# Authorization header turns even public routes into 401s.)
jwt="$(curl -fsS --max-time 25 -A "$UA" -X POST --get \
  --data-urlencode "apiKey=$HANGAR_API_TOKEN" "$API/authenticate" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
if [[ -z "$jwt" ]]; then
  echo "could not exchange HANGAR_API_TOKEN for a session token" >&2
  exit 1
fi

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
printf '%s' "$payload" > "$tmp"
# The body must be read from a Windows-visible path when curl.exe is involved;
# we use the Linux curl here, so a plain temp file is fine.
echo "== current page =="
current="$(curl -fsS --max-time 25 -A "$UA" "$API/pages/main/$SLUG" || true)"
echo "   ${#current} chars"

echo "== push =="
code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -X PATCH -A "$UA" \
  -H "Authorization: Bearer $jwt" -H 'Content-Type: application/json' \
  --data-binary "@$tmp" "$API/pages/editmain/$SLUG")"
if [[ "$code" != "200" ]]; then
  echo "PATCH failed with HTTP $code (the token needs edit_page on project $SLUG)" >&2
  exit 1
fi

sleep 2
updated="$(curl -fsS --max-time 25 -A "$UA" "$API/pages/main/$SLUG")"
expected="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["content"])' "$tmp")"
if [[ "$updated" == "$expected" ]]; then
  echo "ok    main page updated (${#updated} chars) and verified"
elif [[ "$updated" == "${expected%$'\n'}" ]]; then
  echo "ok    main page updated (${#updated} chars, trailing newline trimmed by Hangar)"
else
  echo "warn  page length is ${#updated}, expected ${#expected} - check https://hangar.papermc.io/$SLUG" >&2
  exit 1
fi
