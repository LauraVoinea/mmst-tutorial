#!/usr/bin/env bash
#
# Locate the mMST checker and its classpath; cache it in .classpath.
# Sourced by serve.sh. ./classpath.sh --show prints it.
#
# Classpath, first that works: the cache, sbt's export, target/ plus the Coursier/Ivy cache.

_mmst_dim() { [ -t 2 ] && printf '\033[2m%s\033[0m\n' "$*" >&2 || printf '%s\n' "$*" >&2; }
_mmst_red() { [ -t 2 ] && printf '\033[31m%s\033[0m\n' "$*" >&2 || printf '%s\n' "$*" >&2; }

# The scribble-gt-scala checkout: $MMST_HOME, ./toolchain, or ../scribble-gt-scala.
# ROOT="$(mmst_toolchain_root "$REPO")" || exit 1
mmst_toolchain_root() {
    local repo="$1" cand
    for cand in "${MMST_HOME:-}" "$repo/toolchain" "$repo/../scribble-gt-scala"; do
        [ -n "$cand" ] || continue
        [ -d "$cand" ] || continue
        if [ -f "$cand/build.sbt" ] && [ -f "$cand/mMST.sh" ]; then
            ( cd "$cand" && pwd )
            return 0
        fi
    done
    _mmst_red "No scribble-gt-scala checkout in \$MMST_HOME, $repo/toolchain or $repo/../scribble-gt-scala."
    _mmst_red "Either:  export MMST_HOME=/path/to/scribble-gt-scala"
    _mmst_red "or:      git submodule update --init --depth 1 && (cd toolchain && sbt compile)"
    return 1
}

# Every entry exists; at least three, one a jar.
_mmst_cp_valid() {
    local cp="$1" n=0 e
    [ -n "$cp" ] || return 1
    case "$cp" in *.jar*) ;; *) return 1 ;; esac
    while IFS= read -r e; do
        [ -n "$e" ] || continue
        [ -e "$e" ] || return 1
        n=$((n + 1))
    done <<EOF
$(printf '%s' "$cp" | tr ':' '\n')
EOF
    [ "$n" -ge 3 ]
}

# --- sbt ---

_mmst_cp_from_sbt() {
    local root="$1" log="$2"
    command -v sbt >/dev/null || return 1

    _mmst_dim "sbt: compiling and exporting the classpath (a minute when cold)..."
    ( cd "$root" && sbt -batch -Dsbt.supershell=false \
          "compile" "export Compile/fullClasspath" ) > "$log" 2>&1

    # Last colon-separated jar line, without any [info] prefix.
    tr -d '\r' < "$log" \
      | sed -e 's/^\[info\][[:space:]]*//' \
      | grep -E '^/.*:.*\.jar' \
      | tail -1
}

# --- build outputs ---

_mmst_scala_jar() {   # _mmst_scala_jar <artifact> <version-glob>
    local artifact="$1" verglob="$2" root hit
    for root in \
        "${COURSIER_CACHE:-}/v1/https/repo1.maven.org/maven2/org/scala-lang" \
        "$HOME/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/org/scala-lang" \
        "$HOME/.cache/coursier/v1/https/repo1.maven.org/maven2/org/scala-lang" \
        "$HOME/.ivy2/cache/org.scala-lang"
    do
        [ -d "$root" ] || continue
        hit=$(find "$root/$artifact" -name "${artifact}-${verglob}.jar" 2>/dev/null \
              | grep -v -- '-sources\.jar$' | grep -v -- '-javadoc\.jar$' \
              | sort -V | tail -1)
        [ -n "$hit" ] && { printf '%s' "$hit"; return 0; }
    done
    return 1
}

_mmst_cp_from_build() {
    local root="$1" classes scalaver s3 s2 parts=()

    # Newest non-empty target/scala-*/classes.
    classes=$(ls -dt "$root"/target/scala-*/classes 2>/dev/null | head -1)
    [ -n "$classes" ] && [ -d "$classes" ] || return 1
    find "$classes" -name '*.class' -print -quit 2>/dev/null | grep -q . || return 1
    parts+=("$classes")

    local j
    for j in "$root"/lib/*.jar; do [ -e "$j" ] && parts+=("$j"); done

    # Scala version from build.sbt.
    scalaver=$(sed -n 's/.*scalaVersion[[:space:]]*:=[[:space:]]*"\([^"]*\)".*/\1/p' "$root/build.sbt" 2>/dev/null | head -1)
    [ -n "$scalaver" ] || scalaver=$(basename "$(dirname "$classes")" | sed 's/^scala-//')

    s3=$(_mmst_scala_jar scala3-library_3 "$scalaver") || return 1
    s2=$(_mmst_scala_jar scala-library '2.13.*')       || return 1
    parts+=("$s3" "$s2")

    local IFS=':'; printf '%s' "${parts[*]}"
}

# --- JDK ---
# Class files track sbt's JDK (68 = JDK 24, 69 = 25); an older java throws
# UnsupportedClassVersionError.

# Class-file major version of the build output.
mmst_needed_class_version() {
    local root="$1" f
    f=$(ls -t "$root"/target/scala-*/classes/org/scribble/ext/gt/cli/GTCommandLine2.class 2>/dev/null | head -1)
    [ -z "$f" ] && f=$(find "$root"/target/scala-*/classes -name '*.class' 2>/dev/null | head -1)
    [ -n "$f" ] || return 1
    od -An -tu1 -j6 -N2 "$f" 2>/dev/null | awk '{print $1*256+$2; exit}'
}

# Feature release of a java home, or of `java` on the PATH.
mmst_java_feature() {
    local bin="${1:+$1/bin/}java" v
    command -v "${bin}" >/dev/null 2>&1 || [ -x "$bin" ] || return 1
    v=$("$bin" -XshowSettings:properties -version 2>&1 \
        | sed -n 's/.*java\.specification\.version = \(.*\)/\1/p' | head -1 | tr -d ' ')
    [ -n "$v" ] || return 1
    printf '%s' "${v%%.*}"
}

# JDK homes with a javac, sbt's first.
mmst_jdk_candidates() {
    {
        printf '%s\n' "${JAVA_HOME:-}" "${SBT_JAVA_HOME:-}"
        # java_home -v is a best match, not exact: a candidate only.
        if [ -x /usr/libexec/java_home ]; then
            /usr/libexec/java_home -V 2>&1 \
              | sed -n 's@.*[[:space:]]\(/.*/Contents/Home\)$@\1@p'
        fi
        ls -dt "$HOME/Library/Caches/Coursier/jvm"/*/Contents/Home \
               "$HOME/Library/Caches/Coursier/arc/https"/*/*/*/Contents/Home \
               "$HOME/.cache/coursier/jvm"/*/ \
               "$HOME/.cache/coursier/arc/https"/*/*/*/ \
               "$HOME/Library/Java/JavaVirtualMachines"/*/Contents/Home \
               /Library/Java/JavaVirtualMachines/*/Contents/Home \
               "$HOME/.sdkman/candidates/java"/*/ \
               "$HOME/.jenv/versions"/*/ \
               /opt/homebrew/opt/openjdk*/libexec/openjdk.jdk/Contents/Home \
               /usr/local/opt/openjdk*/libexec/openjdk.jdk/Contents/Home \
               /usr/lib/jvm/*/ 2>/dev/null | sed 's@/$@@'
    } | awk 'NF && !seen[$0]++'
}

# A JDK home with feature release >= $1, checked by running it.
mmst_find_jdk() {
    local need="$1" home f
    while IFS= read -r home; do
        [ -n "$home" ] && [ -x "$home/bin/javac" ] || continue
        f=$(mmst_java_feature "$home") || continue
        case "$f" in ''|*[!0-9]*) continue ;; esac
        if [ "$f" -ge "$need" ]; then printf '%s' "$home"; return 0; fi
    done <<EOF
$(mmst_jdk_candidates)
EOF
    return 1
}

mmst_report_jdks() {
    local home f any=0
    while IFS= read -r home; do
        [ -n "$home" ] && [ -x "$home/bin/javac" ] || continue
        f=$(mmst_java_feature "$home") || continue
        _mmst_red "    Java $f  $home"
        any=1
    done <<EOF
$(mmst_jdk_candidates)
EOF
    [ "$any" = 1 ] || _mmst_red "    (none with javac)"
}

# The JVM sbt runs on: authoritative, but slow. Last resort.
mmst_sbt_java_home() {
    local root="$1" out home
    command -v sbt >/dev/null || return 1
    _mmst_dim "Asking sbt for its JVM..."
    out=$( cd "$root" && sbt -batch -Dsbt.supershell=false \
             'eval System.getProperty("java.home")' 2>/dev/null )
    home=$(printf '%s' "$out" | tr -d '\r' | sed -n 's/.*ans: String = \(.*\)/\1/p' | tail -1)
    [ -n "$home" ] && [ -x "$home/bin/java" ] || return 1
    printf '%s' "$home"
}

# Re-exec the script under a new enough JDK if `java` is too old.
# mmst_reexec_on_jdk "$ROOT" "$0" "$@"
mmst_reexec_on_jdk() {
    local root="$1" script="$2"; shift 2
    [ -n "${MMST_JDK_REEXEC:-}" ] && return 0          # once only

    local major need have home
    major=$(mmst_needed_class_version "$root") || return 0
    need=$((major - 44))
    have=$(mmst_java_feature) || return 0
    [ "$have" -ge "$need" ] 2>/dev/null && return 0

    local f
    home=$(mmst_find_jdk "$need")
    if [ -z "$home" ]; then
        home=$(mmst_sbt_java_home "$root") || home=""
        if [ -n "$home" ]; then
            f=$(mmst_java_feature "$home") || f=0
            case "$f" in ''|*[!0-9]*) f=0 ;; esac
            [ "$f" -ge "$need" ] || home=""
        fi
    fi

    if [ -n "$home" ]; then
        _mmst_dim "Build needs Java $need; PATH has $have. Using $home"
        MMST_JDK_REEXEC=1 JAVA_HOME="$home" PATH="$home/bin:$PATH" exec "$script" "$@"
    fi

    _mmst_red "Build needs Java $need (class file $major); PATH has $have; none newer found:"
    mmst_report_jdks
    _mmst_red "Rebuild with your JDK (clean is required):"
    _mmst_red "    cd $root && JAVA_HOME=\"\$(/usr/libexec/java_home -v $have)\" sbt clean compile"
    return 1
}

# --- entry ---

# mmst_classpath <checkout> <cache-file>: print the classpath, or fail.
mmst_classpath() {
    local root="$1" cache="$2" cp log
    log="${TMPDIR:-/tmp}/mmst-sbt-$$.log"

    if [ -s "$cache" ]; then
        cp=$(cat "$cache")
        if _mmst_cp_valid "$cp"; then printf '%s' "$cp"; return 0; fi
        _mmst_dim "Stale $cache; rebuilding it."
    fi

    cp=$(_mmst_cp_from_sbt "$root" "$log")
    if _mmst_cp_valid "$cp"; then
        printf '%s' "$cp" > "$cache"
        rm -f "$log"
        printf '%s' "$cp"; return 0
    fi
    local sbt_failed=1

    cp=$(_mmst_cp_from_build "$root")
    if _mmst_cp_valid "$cp"; then
        [ "$sbt_failed" = 1 ] && _mmst_dim "sbt export failed; using target/ and the Coursier cache."
        printf '%s' "$cp" > "$cache"
        rm -f "$log"
        printf '%s' "$cp"; return 0
    fi

    rm -f "$cache"                                      # no empty cache file

    _mmst_red "No classpath."
    if [ -s "$log" ]; then
        _mmst_red "sbt said ($log):"
        tail -15 "$log" >&2
    elif ! command -v sbt >/dev/null; then
        _mmst_red "No sbt on the PATH, and nothing compiled in target/scala-*/classes."
    fi
    _mmst_red "Fix:  cd $root && sbt compile"
    _mmst_red "or:   sbt 'export Compile/fullClasspath' | tail -1 > $cache"
    return 1
}

if [ "${BASH_SOURCE[0]}" = "${0}" ] && [ "${1:-}" = "--show" ]; then
    _here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    _root="$(mmst_toolchain_root "$_here")" || exit 1
    if _cp=$(mmst_classpath "$_root" "$_here/.classpath"); then
        printf '%s\n' "$_cp" | tr ':' '\n' | sed 's/^/  /'
        printf '\n%s entries\n' "$(printf '%s' "$_cp" | tr ':' '\n' | grep -c .)"
    else
        exit 1
    fi
fi
