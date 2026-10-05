# The playground, with Erlang runs on for everyone, each confined (see README).
#
#   docker build -t mmst-playground .
#   docker run --rm -p 8080:10000 mmst-playground

# The checker commit the toolchain submodule pins.
ARG TOOLCHAIN_COMMIT=eea778b1fd5799017a8a374352fd3328dbf8c134

# --- build ---
FROM eclipse-temurin:21-jdk AS build
ARG TOOLCHAIN_COMMIT
ARG SCALA_VERSION=3.3.6
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl git gcc libc6-dev \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /build

RUN git init -q toolchain \
 && git -C toolchain fetch -q --depth 1 https://github.com/rhu1/scribble-gt-scala.git "$TOOLCHAIN_COMMIT" \
 && git -C toolchain checkout -q FETCH_HEAD

# As sbt would: ANTLR parser, scalac over Scala and Java, javac over Java, resources.
RUN curl -fsSL "https://github.com/scala/scala3/releases/download/$SCALA_VERSION/scala3-$SCALA_VERSION.tar.gz" \
      | tar -xz -C /opt \
 && mv "/opt/scala3-$SCALA_VERSION" /opt/scala3
RUN cd toolchain \
 && java -cp lib/antlr-3.5.2-complete.jar org.antlr.Tool -o /build/antlr \
      src/main/antlr3/org/scribble/parser/antlr/GTScribble.g \
 && mkdir -p /build/jars /build/checker \
 && cp lib/*.jar /opt/scala3/lib/scala3-library_3-*.jar /opt/scala3/lib/scala-library-*.jar /build/jars/ \
 && JARS="$(ls /build/jars/*.jar | tr '\n' ':')" \
 && find src/main /build/antlr -name '*.scala' -o -name '*.java' > /tmp/sources \
 && /opt/scala3/bin/scalac -nowarn -d /build/checker -classpath "$JARS" @/tmp/sources \
 && find src/main /build/antlr -name '*.java' > /tmp/java \
 && javac -nowarn -d /build/checker -cp "/build/checker:$JARS" @/tmp/java \
 && cp -r src/main/resources/. /build/checker/

# Each Erlang app's src and README.
RUN mkdir -p /build/erlang \
 && cd toolchain/examples/erlang \
 && for app in */; do \
      if [ -d "$app/src" ]; then \
        mkdir -p "/build/erlang/$app" && cp -r "$app/src" "/build/erlang/$app"; \
        if [ -f "$app/README.md" ]; then cp "$app/README.md" "/build/erlang/$app"; fi; \
      fi; \
    done

COPY Playground.java nonet.c /build/pg/
RUN mkdir -p /build/classes \
 && javac -nowarn -d /build/classes /build/pg/Playground.java \
 && cc -O2 -static -o /build/mmst-nonet /build/pg/nonet.c

# --- runtime ---
FROM erlang:27-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends tini \
 && rm -rf /var/lib/apt/lists/*
COPY --from=eclipse-temurin:21-jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:$PATH LANG=C.UTF-8

COPY --from=build /build/jars /app/jars
COPY --from=build /build/checker /app/checker
COPY --from=build /build/classes /app/classes
COPY --from=build /build/mmst-nonet /usr/local/bin/mmst-nonet
COPY --from=build /build/toolchain/examples/scribble /app/examples/scribble
COPY --from=build /build/erlang /app/examples/erlang
COPY web /app/web
COPY mmst_run.erl container.sh /app/
COPY tutorial/exercise1/*.scr /app/tutorial/exercise1/
COPY tutorial/exercise2/*.scr /app/tutorial/exercise2/
RUN mkdir -p /var/lib/mmst/runs && chmod 711 /var/lib/mmst/runs \
 && chmod 755 /app/container.sh \
 && for c in tini setpriv prlimit erl erlc java mmst-nonet; do \
      command -v "$c" > /dev/null || { echo "$c is missing from the image" >&2; exit 1; }; \
    done

# The server runs as root to give each run its own uid; runs never do.
EXPOSE 10000
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["/app/container.sh"]
