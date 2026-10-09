# mMST playground

A browser front end for the mMST checker,
[scribble-gt-scala](https://github.com/rhu1/scribble-gt-scala/tree/artifact): check a mixed-choice protocol,
step through each role's EFSM, and edit and run its Erlang. From *Mixed Choice in Asynchronous
Multiparty Session Types*, OOPSLA 2026, <https://doi.org/10.1145/3798256>.

## Run

```sh
./serve.sh                  # http://localhost:8080
./serve.sh 9000             # another port
MMST_ERLANG=1 ./serve.sh    # with Erlang runs, for localhost
```

Needs a JDK 17+ and a compiled checker: `$MMST_HOME`, the `toolchain` submodule, or
`../scribble-gt-scala`. For the submodule:

```sh
git submodule update --init --depth 1 && (cd toolchain && sbt compile)
```

The classpath is cached in `.classpath`. Erlang runs need `erl` and `erlc` on the PATH.

## Files

| | |
|---|---|
| `Playground.java` | the server, on the JDK's `com.sun.net.httpserver` |
| `Diagnose.java` | runs the checker; when it rejects a protocol, finds the lines |
| `web/` | `index.html` (playground), `examples.html`, `mixed-choice.html`, and their scripts |
| `mmst_run.erl` | compiles and runs a set of modules in a fresh VM; reports each role |
| `serve.sh`, `classpath.sh` | build and run locally |
| `Dockerfile`, `container.sh`, `nonet.c`, `render.yaml` | the container image, and Render |
| `tutorial/exercise1`, `tutorial/exercise2` | the exercises in the menu |
| `toolchain` | the checker, pinned at `eea778b` |

## API

| | |
|---|---|
| `POST /api/validate {"source"}` | flags, problems with their lines, local types |
| `POST /api/generate {"source"}` | Erlang modules |
| `POST /api/efsm {"source"}` | EFSM per role |
| `GET /api/examples` | menu protocols |
| `GET /api/erlang/list`, `GET /api/erlang/files?id=` | Erlang sets |
| `POST /api/erlang/run {"files"}` | compile, run for up to 5 s, report |
| `GET /api/health` | `{"ok":true}` |

Each check runs in a child JVM, since the checker exits on parse errors, with a deadline and a
heap cap. Responses are cached by protocol text. `-gt-check-fidelity` and
`-gt-check-completeness` are not exposed.

## Erlang mode

Sets: the artifact's filled-in apps (`examples/erlang`), whatever `./mMST.sh` wrote to
`generated/`, and modules generated from the editor. Edits are kept per set for the session;
`.zip` downloads them.

**Run** compiles every module in a fresh VM, `gen_` modules first, starts the roles from the
`*_sup` child specs or else every `gen_` implementer, and reports each role as finished,
crashed (with the line) or waiting (with its state and postponed events). A `gen_` module whose
name OTP uses (role `server`, `gen_server`) is renamed for the run. Generated templates stall on
the pid patterns in `#state_data{}`; the page offers to drop them.

## Erlang runs

A run executes what it is sent, so runs are off unless switched on:

| `MMST_ERLANG` | runs for |
|---|---|
| unset, `off` | nobody; the Erlang mode still opens and edits |
| `1` | pages at `localhost` on the server's machine |
| `all` | anyone who can reach the server: containers only |

Only the playground's own page may run code. Each run gets a fresh VM and folder, a 5 s
deadline, 128 MB of heap per process, and 300 KB of output. The server also confines runs
as far as the machine allows, probed at start-up:

- own user per run, when the server is root (`setpriv`); leftovers killed and deleted
- no network: an empty network namespace (`unshare`), else `mmst-nonet` (`nonet.c`), a seccomp filter
- limits on memory, processes, open files, file size and CPU time (`prlimit`)

The log and the Erlang mode say which apply; on a Mac, none. With `all`, runs stay off unless
each gets its own user and no network. At start-up the server makes one trivial run, and
switches runs off if it fails.

## Deploy

```sh
docker build -t mmst-playground .
docker run --rm -p 8080:10000 mmst-playground     # http://localhost:8080
```

The image builds the checker at the pinned commit, without sbt or the submodule, adds
Erlang/OTP 27, and runs with `MMST_ERLANG=all`. `container.sh` sizes it to the container:

| Variable | | 512 MB | 2 GB | more |
|---|---|---|---|---|
| `MMST_SLOTS` | checks at once | 1 | 2 | 4 |
| `MMST_ERLANG_SLOTS` | runs at once | 1 | 2 | 3 |
| `MMST_ERLANG_MEMORY` | MB per run | 384 | 640 | 768 |
| `MMST_HEAP`, `MMST_CHECKER_HEAP` | server, check heap | 160m, 192m | 384m, 256m | 512m, 256m |

`MMST_DEADLINE`: ms per check, 15000, or 90000 below half a CPU. `MMST_ERLANG=off` switches runs
off. `serve.sh` reads `MMST_SLOTS` and `MMST_ERLANG_SLOTS`.


## Security
Requests are capped at 64 KB; checks at 15 s, 256 MB and 400 000 characters of output. Only `.scr` files
directly in the exercise folders and `examples/scribble` are listed, by short path; no request
chooses a file. Generated files are deleted after each response.
