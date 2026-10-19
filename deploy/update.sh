#!/usr/bin/env bash
#
# mmst-update: set the server's copy of the repo to GitHub's main, rebuild the image, restart
# the playground and check it. With --fresh, also pull new Erlang and Java images, for their
# fixes; the build then takes a few minutes. The copy follows GitHub: edits to it are lost.

set -euo pipefail

SRC=/opt/mmst/src
URL=http://127.0.0.1:10000

[ "$(id -u)" -eq 0 ] || { echo "mmst-update: run as root" >&2; exit 1; }

# Fetch, then go on with the fetched version of this script.
if [ "${MMST_PULLED:-}" != 1 ]; then
  git -C "$SRC" fetch -q origin main
  git -C "$SRC" reset -q --hard FETCH_HEAD
  MMST_PULLED=1 exec bash "$SRC/deploy/update.sh" "$@"
fi

echo "Building $(git -C "$SRC" log -1 --format='%h, %cs: %s')"
build=(docker build -t mmst-playground)
if [ "${1:-}" = --fresh ]; then build+=(--pull); fi
"${build[@]}" "$SRC"

install -m 0644 "$SRC/deploy/mmst.service" /etc/systemd/system/mmst.service
systemctl daemon-reload
systemctl reenable -q mmst
systemctl restart mmst
docker image prune -f > /dev/null

printf 'Starting'
for _ in $(seq 90); do
  if curl -fsS -o /dev/null "$URL/api/health" 2> /dev/null; then break; fi
  printf '.'
  sleep 2
done
echo

# /api/health answers whatever the checker does, so check a protocol too.
body='{"source":"module Ping;\nglobal protocol Ping(role A, role B) { ping() from A to B; }\n"}'
reply=$(curl -sS --max-time 90 -H 'Content-Type: application/json' --data "$body" "$URL/api/validate" || true)
case "$reply" in
  '{"ok":true'*) echo "The playground is up, and checks protocols." ;;
  *) echo "mmst-update: the playground did not check a protocol. Its log:" >&2
     journalctl -u mmst -n 40 --no-pager >&2
     exit 1 ;;
esac

# Its first lines: the sizing, and whether Erlang runs are on and confined.
log=""
for _ in $(seq 15); do
  log=$(docker logs mmst 2>&1 || true)
  if grep -q '^  runs ' <<< "$log"; then break; fi
  sleep 2
done
sed -n '1,9p' <<< "$log"
if grep -q '^  runs  *off' <<< "$log"; then
  echo "Erlang runs are off; the line above says why."
fi
