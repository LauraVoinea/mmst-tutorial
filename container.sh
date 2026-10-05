#!/bin/sh
#
# Docker entry point: size the playground to the container, run it, Erlang runs on.
# Overrides: MMST_HEAP, MMST_SLOTS, MMST_CHECKER_HEAP, MMST_DEADLINE,
#            MMST_ERLANG (on|all|off), MMST_ERLANG_SLOTS, MMST_ERLANG_MEMORY (MB).
set -eu

# Memory limit in MB from the cgroup (v2, then v1); 0 if none.
mem=$(cat /sys/fs/cgroup/memory.max 2>/dev/null || cat /sys/fs/cgroup/memory/memory.limit_in_bytes 2>/dev/null || echo max)
case "$mem" in ''|max) mem_mb=0 ;; *) mem_mb=$((mem / 1048576)) ;; esac
[ "$mem_mb" -gt 1048576 ] && mem_mb=0

# CPU quota in hundredths; 100 if none.
cpu=$(awk '$1 != "max" { printf "%d", 100 * $1 / $2 }' /sys/fs/cgroup/cpu.max 2>/dev/null || true)
if [ -z "$cpu" ]; then
  q=$(cat /sys/fs/cgroup/cpu/cpu.cfs_quota_us 2>/dev/null || echo -1)
  p=$(cat /sys/fs/cgroup/cpu/cpu.cfs_period_us 2>/dev/null || echo 100000)
  if [ "$q" -gt 0 ]; then cpu=$((100 * q / p)); else cpu=100; fi
fi

if [ "$mem_mb" -gt 0 ] && [ "$mem_mb" -lt 1500 ]; then      # 512 MB
  heap=160m checker=192m slots=1 eslots=1 emem=384
elif [ "$mem_mb" -gt 0 ] && [ "$mem_mb" -lt 3500 ]; then    # 2 GB
  heap=384m checker=256m slots=2 eslots=2 emem=640
else
  heap=512m checker=256m slots=4 eslots=3 emem=768
fi
deadline=15000
[ "$cpu" -lt 50 ] && deadline=90000                          # slow JVM starts

echo "container: ${mem_mb:-?} MB, $cpu/100 CPU: heap ${MMST_HEAP:-$heap}, ${MMST_SLOTS:-$slots} checks and ${MMST_ERLANG_SLOTS:-$eslots} runs at once"

exec java -XX:+UseSerialGC -Xmx"${MMST_HEAP:-$heap}" -Dfile.encoding=UTF-8 \
  -Dmmst.slots="${MMST_SLOTS:-$slots}" \
  -Dmmst.deadline="${MMST_DEADLINE:-$deadline}" \
  -Dmmst.checker.heap="${MMST_CHECKER_HEAP:-$checker}" \
  -Dmmst.exercises=/app/tutorial \
  -Dmmst.examples=/app/examples/scribble \
  -Dmmst.erlang="${MMST_ERLANG:-all}" \
  -Dmmst.erlang.slots="${MMST_ERLANG_SLOTS:-$eslots}" \
  -Dmmst.erlang.memory="${MMST_ERLANG_MEMORY:-$emem}" \
  -Dmmst.erlang.examples=/app/examples/erlang \
  -Dmmst.erlang.runs=/var/lib/mmst/runs \
  -Dmmst.launcher=/app/mmst_run.erl \
  -cp "/app/classes:/app/checker:/app/jars/*" \
  Playground "${PORT:-10000}" /app/web
