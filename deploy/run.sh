#!/bin/sh
#
# The playground's container, in the foreground, for the service mmst.
#
# Its memory limit is the machine's memory less 640 MB for the system, Docker and Caddy, and
# container.sh sizes the playground to that limit: after a resize, a restart resizes it too.
# /opt/mmst/env, if there, sets the container's variables, one NAME=value a line.

set -eu

total=$(awk '/^MemTotal:/ { print int($2 / 1024) }' /proc/meminfo)
limit=$((total - 640))
if [ -f /opt/mmst/env ]; then set -- --env-file /opt/mmst/env; fi

exec docker run --rm --name mmst --pull never \
  --publish 127.0.0.1:10000:10000 \
  --memory "${limit}m" --memory-swap "${limit}m" \
  --log-driver local \
  "$@" mmst-playground
