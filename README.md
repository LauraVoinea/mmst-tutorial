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
| `web/` | `index.html` (playground), `examples.html`, `mixed-choice.html`, `plug.html` (talk slides), and their scripts |
| `web/text/` | the pages' text, in Markdown: `mixed-choice.md`, `examples.md` (example descriptions and menu order), `plug.md` (slides) |
| `web/header.html`, `web/footer.html` | the bars on every page; `shared.js` puts them in |
| `web/parts/` | HTML parts that the Markdown pulls in with `<div data-include="parts/…">` |
| `web/vendor/` | [marked](https://github.com/markedjs/marked) 18.0.14, MIT licence: renders the Markdown |
| `mmst_run.erl` | compiles and runs a set of modules in a fresh VM; reports each role |
| `serve.sh`, `classpath.sh` | build and run locally |
| `Dockerfile`, `container.sh`, `nonet.c`, `render.yaml` | the container image, and Render |
| `deploy/` | a server of your own: the setup for Ubuntu 24.04, the service, `mmst-update` |
| `tutorial/exercise1`, `tutorial/exercise2`, `tutorial/exercise4` | the exercises in the menu |
| `tutorial/extras/slides` | the protocols from the workshop's slides, on the examples page |
| `toolchain` | the checker, pinned at `f5872a3` |

## API

| | |
|---|---|
| `POST /api/validate {"source"}` | flags, problems with their lines, local types |
| `POST /api/generate {"source"}` | Erlang modules |
| `POST /api/efsm {"source"}` | EFSM per role |
| `GET /api/examples` | the protocols for the menu and the examples page |
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

## Deploy on Linode

`deploy/` runs the playground on a server of your own with Ubuntu 24.04: Docker for the
container, Caddy in front of it for HTTPS, and a service, `mmst`, that keeps it running. On
Linode:

1. Create a Linode: Ubuntu 24.04 LTS, London or Frankfurt, Shared CPU, Linode 2 GB. Add your
   SSH key.
2. Pick its hostname. With a domain of your own, add an A record with the Linode's IPv4
   address. Without one, the Linode's own name works: its reverse DNS, on the Network tab,
   such as `192-0-2-1.ip.linodeusercontent.com`.
3. Log in as root and run the setup, about ten minutes:

   ```sh
   curl -fsSLO https://raw.githubusercontent.com/LauraVoinea/mmst-tutorial/main/deploy/linode-setup.sh
   bash linode-setup.sh play.example.org
   ```

The setup installs updates and turns on daily security updates, with a reboot at 03:30 UTC
when one needs it. It adds 2 GB of swap and a firewall that lets in SSH, HTTP and HTTPS only,
installs Docker and Caddy from Ubuntu, and turns SSH passwords off if root has a key. Then it
builds the image from `main` on GitHub, starts the service and checks a protocol. It ends with
the playground's first log lines, where runs should be on:
`Each run: own user, no network, limits.` Run it again to change the hostname.

| On the server | |
|---|---|
| `mmst-update` | set the copy in `/opt/mmst/src` to GitHub's `main`, rebuild, restart, check; `--fresh` also pulls new Erlang and Java images |
| `journalctl -u mmst -f` | the playground's log |
| `systemctl restart mmst` | restart it |
| `/opt/mmst/env` | the container's variables, one `NAME=value` a line, read at each restart; `MMST_ERLANG=off` stops runs |

The container gets the machine's memory less 640 MB, and `container.sh` sizes the playground
to that: on a Linode 2 GB, one check and one run at once. For the workshop, resize the Linode
the day before to Dedicated CPU, Dedicated 8 GB: four checks and three runs at once, about
$0.11 an hour. Untick *Auto Resize Disk*, so that it can go back to Linode 2 GB afterwards. A
resize reboots the Linode, and the playground starts again at the new size.

No certificate: check that the hostname points at the Linode, and that a Cloud Firewall, if
the Linode has one, lets in ports 80 and 443; then `systemctl restart caddy`.


## Security
Requests are capped at 64 KB; checks at 15 s, 256 MB and 400 000 characters of output. Only `.scr` files
directly in the exercise folders, `extras/slides` and `examples/scribble` are listed, by
short path; no request chooses a file. Generated files are deleted after each response.
