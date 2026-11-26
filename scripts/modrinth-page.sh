#!/usr/bin/env bash
# Push the shared long description and one-line summary from
# docs/operations/platform-listings.md to the Modrinth project page.
#
# Modrinth exposes PATCH /v2/project/{id} (scope WRITE_PROJECT), so the one-liner
# and the long description can be kept in sync from the repo instead of being
# pasted by hand. Modrinth's project model calls the one-liner `description`
# (`summary` is silently ignored). Name, slug, icon, gallery and the moderation
# state stay manual.
set -euo pipefail
source "$(dirname "$0")/_common.sh"

PROJECT_ID="${MODRINTH_PROJECT_ID:-pRGtDpaD}"
DOC="$ROOT_DIR/docs/operations/platform-listings.md"
API="https://api.modrinth.com/v2"
UA="OrzMC/RealTime modrinth-page"

if [[ -z "${MODRINTH_TOKEN:-}" ]]; then
  echo "MODRINTH_TOKEN is required (create a PAT at https://modrinth.com/settings/pats with WRITE_PROJECT)." >&2
  exit 2
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
payload_file="$work/payload.json"

python3 - "$DOC" > "$payload_file" <<'PY'
import json, re, sys
doc = open(sys.argv[1], encoding="utf-8").read()
body = re.search(r"````markdown\n(.*?)\n````", doc, re.S)
summary = re.search(r"\| 一句话简介 \| (.+?) \|", doc)
if not body or not summary:
    sys.exit("platform-listings.md needs both a ````markdown block and a 一句话简介 row")
text = summary.group(1).strip().strip("`").strip()
if not text.isascii():
    sys.exit("the 一句话简介 row must be the English one-liner used on the platform pages")
print(json.dumps({"description": text, "body": body.group(1)}))
PY

python3 - "$payload_file" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
print(f"   description: {len(d['description'])} chars")
print(f"   body: {len(d['body'])} chars")
PY

echo "== push =="
code="$(curl -s -o "$work/response.json" -w '%{http_code}' --max-time 30 \
  -X PATCH -A "$UA" \
  -H "Authorization: $MODRINTH_TOKEN" -H 'Content-Type: application/json' \
  --data-binary "@$payload_file" "$API/project/$PROJECT_ID")"
if [[ "$code" != "204" ]]; then
  echo "PATCH failed with HTTP $code (the token needs WRITE_PROJECT on $PROJECT_ID):" >&2
  head -c 400 "$work/response.json" >&2 || true
  exit 1
fi

sleep 2
curl -s --max-time 25 -A "$UA" -H "Authorization: $MODRINTH_TOKEN" \
  "$API/project/$PROJECT_ID" -o "$work/project.json"

python3 - "$payload_file" "$work/project.json" <<'PY'
import json, sys
payload = json.load(open(sys.argv[1]))
project = json.load(open(sys.argv[2]))
description = project.get("description") or ""
body = project.get("body") or ""
ok = True
if description != payload["description"]:
    print(
        f"warn  description is {len(description)} chars, expected {len(payload['description'])} "
        "- a project under review may still hold the old text",
        file=sys.stderr,
    )
    ok = False
if body != payload["body"]:
    print(
        f"warn  body is {len(body)} chars, expected {len(payload['body'])} "
        "- a project under review may still hold the old text",
        file=sys.stderr,
    )
    ok = False
if ok:
    print(f"ok    description ({len(description)} chars) and body ({len(body)} chars) updated and verified")
PY
