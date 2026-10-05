#!/usr/bin/env bash
#
# Build and run the playground.
#
#   ./serve.sh [port]               http://localhost:8080
#   MMST_ERLANG=1 ./serve.sh        Erlang runs, for localhost
#
# Env: MMST_HOME (checker checkout), MMST_SLOTS, MMST_ERLANG_SLOTS.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT="${1:-8080}"
CLASSES="$HERE/classes"

red() { [ -t 2 ] && printf '\033[31m%s\033[0m\n' "$*" >&2 || printf '%s\n' "$*" >&2; }
dim() { [ -t 2 ] && printf '\033[2m%s\033[0m\n'  "$*" >&2 || printf '%s\n' "$*" >&2; }

command -v java  >/dev/null || { red "No java: need a JDK 17+."; exit 1; }
command -v javac >/dev/null || { red "No javac: need a JDK 17+, ideally the one sbt uses."; exit 1; }

# shellcheck source=classpath.sh
. "$HERE/classpath.sh"

ROOT="$(mmst_toolchain_root "$HERE")" || exit 1
mmst_reexec_on_jdk "$ROOT" "$0" "$@" || exit 1          # a JDK that loads sbt's classes
CP="$(mmst_classpath "$ROOT" "$HERE/.classpath")" || exit 1

mkdir -p "$CLASSES"
if [ ! -f "$CLASSES/Playground.class" ] || [ "$HERE/Playground.java" -nt "$CLASSES/Playground.class" ]; then
  dim "Compiling Playground.java..."
  javac -nowarn -d "$CLASSES" -cp "$CP" "$HERE/Playground.java" || exit 1
fi

EXAMPLES="$ROOT/examples/scribble"
[ -d "$EXAMPLES" ] || dim "No $EXAMPLES: exercises only."

case "${MMST_ERLANG:-}" in
  1|on|yes|true) ERLANG=on ;;
  all)           ERLANG=all ;;
  *)             ERLANG=off ;;
esac
if [ "$ERLANG" != off ] && ! { command -v erl && command -v erlc; } >/dev/null; then
  red "MMST_ERLANG is set, but erl or erlc is missing: runs off."
fi

# Preflight on a real protocol: -h passes even when a JDK mismatch breaks checks.
PRE="$(mktemp -d)"
printf 'module Preflight;\nglobal protocol Preflight(role A, role B) { x() from A to B; }\n' > "$PRE/Preflight.scr"
PREFLIGHT="$(java -Dfile.encoding=UTF-8 -cp "$CP" com.github.rhu1.gt.main.Main "$PRE/Preflight.scr" 2>&1)"
rm -rf "$PRE"
if ! printf '%s' "$PREFLIGHT" | grep -q ': OK'; then
  red "The checker does not run:"
  printf '%s\n' "$PREFLIGHT" | grep -iE 'error|exception|version' | head -4 >&2
  red "On UnsupportedClassVersionError: use sbt's JDK, or  cd $ROOT && sbt clean compile"
  exit 1
fi

dim "Starting on http://localhost:$PORT"
exec java \
  ${MMST_SLOTS:+-Dmmst.slots=$MMST_SLOTS} \
  ${MMST_ERLANG_SLOTS:+-Dmmst.erlang.slots=$MMST_ERLANG_SLOTS} \
  -Dmmst.exercises="$HERE/tutorial" \
  -Dmmst.examples="$EXAMPLES" \
  -Dmmst.erlang="$ERLANG" \
  -Dmmst.erlang.examples="$ROOT/examples/erlang" \
  -Dmmst.erlang.generated="$ROOT/generated" \
  -Dmmst.launcher="$HERE/mmst_run.erl" \
  -Dfile.encoding=UTF-8 \
  -Xmx512m \
  -cp "$CLASSES:$CP" \
  Playground "$PORT" "$HERE/web"
