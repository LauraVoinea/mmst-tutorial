import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/*
 * mMST playground server.
 *
 *   java -cp <checker classpath>:classes Playground [port] [webroot]
 *
 *   POST /api/validate        {"source"}   flags, local types, errors
 *   POST /api/generate        {"source"}   Erlang modules
 *   POST /api/efsm            {"source"}   EFSM per role
 *   GET  /api/examples                     menu protocols
 *   GET  /api/erlang/list                  Erlang sets
 *   GET  /api/erlang/files?id=             one set's files
 *   POST /api/erlang/run      {"files"}    compile and run; off unless -Dmmst.erlang=on|all
 *
 * Each check runs in a child JVM: the checker calls System.exit on parse errors.
 * -gt-check-fidelity and -gt-check-completeness are not exposed: unbounded.
 */
public final class Playground {

    private static final int  MAX_SOURCE_BYTES = 64 * 1024;
    private static final int  MAX_OUTPUT_CHARS = 400_000;
    private static final long DEADLINE_MS      = Long.getLong("mmst.deadline", 15_000);

    // Responses by endpoint and source, LRU.
    private static final java.util.LinkedHashMap<String, String> CACHE =
        new java.util.LinkedHashMap<>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, String> e) { return size() > 512; }
        };

    private static String cached(String key) { synchronized (CACHE) { return CACHE.get(key); } }
    private static String cache(String key, String value) {
        if (value != null && !value.contains("\"stage\":\"error\"") || value != null && value.contains("\"code\":"))
            synchronized (CACHE) { CACHE.put(key, value); }
        return value;
    }

    // Concurrent checker JVMs.
    private static final java.util.concurrent.Semaphore SLOTS =
        new java.util.concurrent.Semaphore(
            Integer.getInteger("mmst.slots", Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors()))));

    private static final Pattern MODULE   = Pattern.compile("(?m)^\\s*module\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;");
    private static final Pattern PROTOCOL = Pattern.compile("(?m)^\\s*global\\s+protocol\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*[(<]");
    private static final Pattern FLAG     = Pattern.compile("(?m)^(WF|SD|CT|BA)=(true|false)$");
    private static final Pattern CULPRIT  = Pattern.compile("(?m)^WFqqqq:\\s*(.+)$");
    private static final Pattern LOCAL    = Pattern.compile("(?m)^([A-Za-z0-9_.]+)@([A-Za-z0-9_]+):\\s(.+)$");
    private static final Pattern MISMATCH = Pattern.compile("Simple module name at path .*? mismatch: (\\S+)");

    private static Path webroot;

    // Menu sources: exerciseN/ folders, and the checker's examples/scribble.
    private static Path exercises, artifactExamples;

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        webroot  = Paths.get(args.length > 1 ? args[1] : ".").toAbsolutePath().normalize();
        exercises        = folder("mmst.exercises", webroot.resolveSibling("tutorial"));
        artifactExamples = folder("mmst.examples", null);
        // Erlang: examples/erlang beside examples/scribble; generated/ at the checkout root.
        erlExamples  = folder("mmst.erlang.examples", artifactExamples == null ? null : artifactExamples.resolveSibling("erlang"));
        erlGenerated = folder("mmst.erlang.generated",
            artifactExamples == null || artifactExamples.getParent() == null ? null : artifactExamples.getParent().resolveSibling("generated"));
        erlangSetup();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/validate", ex -> handle(ex, Playground::validate));
        server.createContext("/api/generate", ex -> handle(ex, Playground::generate));
        server.createContext("/api/efsm",     ex -> handle(ex, Playground::efsm));
        server.createContext("/api/examples", Playground::examples);
        server.createContext("/api/erlang/list",  Playground::erlangList);
        server.createContext("/api/erlang/files", Playground::erlangFiles);
        server.createContext("/api/erlang/run",   Playground::erlangRun);
        server.createContext("/api/health",   ex -> send(ex, 200, "application/json", "{\"ok\":true}"));
        server.createContext("/", Playground::statics);
        server.setExecutor(Executors.newFixedThreadPool(16));   // a run holds its thread for up to ~30 s
        server.start();

        try { validate("module Warmup;\nglobal protocol Warmup(role A, role B) { x() from A to B; }"); }
        catch (Throwable ignored) { }

        System.out.println("playground  http://localhost:" + port + "  web " + webroot);
        System.out.println("  exercises " + (isDir(exercises) ? exercises : "none (-Dmmst.exercises)"));
        System.out.println("  examples  " + (isDir(artifactExamples) ? artifactExamples : "none (-Dmmst.examples)"));
        System.out.println("  erlang    " + (isDir(erlExamples) ? erlExamples : "none")
                         + (isDir(erlGenerated) ? ", " + erlGenerated : ""));
        System.out.println("  runs      " + (erlOff != null ? "off: " + erlOffWhy
                                          : erlAll ? "on, for everyone" : "on, localhost only"));
        if (erlOff == null) System.out.println("            OTP " + otp + ", " + erl);
        if (erlOff == null) System.out.println("            " + jailed);
    }

    /* --- routes --- */

    private interface Endpoint { String apply(String source) throws Exception; }

    private static void handle(HttpExchange ex, Endpoint fn) throws IOException {
        cors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { send(ex, 204, "text/plain", ""); return; }
        if (!"POST".equals(ex.getRequestMethod()))   { send(ex, 405, "application/json", err("Use POST.")); return; }

        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String source = jsonGet(body, "source");
        if (source == null || source.isBlank()) { send(ex, 200, "application/json", err("Empty protocol.")); return; }
        if (source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            send(ex, 200, "application/json", err("Protocol over 64 KB."));
            return;
        }
        if (!source.endsWith("\n")) source += "\n";   // the grammar needs a newline after a trailing // comment

        String key = ex.getHttpContext().getPath() + "\u0000" + source;
        String hit = cached(key);
        if (hit != null) { send(ex, 200, "application/json", hit); return; }

        String out;
        try { out = cache(key, fn.apply(source)); }
        catch (Exception e) { out = err("Checker failed: " + safe(String.valueOf(e.getMessage()))); }
        send(ex, 200, "application/json", out);
    }

    private static final Pattern EXERCISE_DIR = Pattern.compile("exercise(\\d{1,3})");

    // Menu: each exerciseN/ folder, then examples/scribble. Only .scr files directly inside.
    private static void examples(HttpExchange ex) throws IOException {
        cors(ex);
        StringBuilder sb = new StringBuilder("{\"examples\":[");
        int n = 0;
        if (isDir(exercises)) {
            List<Path> dirs;
            try (Stream<Path> s = Files.list(exercises)) {
                dirs = s.filter(p -> EXERCISE_DIR.matcher(p.getFileName().toString()).matches() && Files.isDirectory(p))
                        .sorted(Comparator.comparingInt(Playground::exerciseNumber))
                        .toList();
            }
            for (Path d : dirs) {
                int k = exerciseNumber(d);
                for (Path p : protocols(d))
                    n = entry(sb, n, k + "-" + stem(p), "Exercise " + k, shown(exercises) + d.getFileName() + "/" + p.getFileName(), p);
            }
        }
        if (isDir(artifactExamples)) {
            String where = shown(artifactExamples.getParent()) + shown(artifactExamples);
            for (Path p : protocols(artifactExamples))
                n = entry(sb, n, stem(p), "Examples", where + p.getFileName(), p);
        }
        send(ex, 200, "application/json", sb.append("]}").toString());
    }

    // Unreadable files are skipped.
    private static int entry(StringBuilder sb, int n, String id, String group, String path, Path file) {
        String src;
        try { src = Files.readString(file, StandardCharsets.UTF_8); }
        catch (IOException | RuntimeException e) { return n; }
        if (n > 0) sb.append(',');
        sb.append("{\"id\":").append(jsonStr(id))
          .append(",\"group\":").append(jsonStr(group))
          .append(",\"path\":").append(jsonStr(path))
          .append(",\"source\":").append(jsonStr(src)).append('}');
        return n + 1;
    }

    private static List<Path> protocols(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".scr") && Files.isRegularFile(p))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    private static int exerciseNumber(Path dir) {
        return Integer.parseInt(dir.getFileName().toString().substring("exercise".length()));
    }

    private static String stem(Path p) { return p.getFileName().toString().replaceAll("\\.scr$", ""); }

    private static boolean isDir(Path p) { return p != null && Files.isDirectory(p); }

    // "name/": never the server path.
    private static String shown(Path dir) {
        Path name = dir == null ? null : dir.getFileName();
        return name == null ? "" : name + "/";
    }

    private static Path folder(String property, Path fallback) {
        String v = System.getProperty(property);
        Path p = v != null && !v.isBlank() ? Paths.get(v) : fallback;
        return p == null ? null : p.toAbsolutePath().normalize();
    }

    private static void statics(HttpExchange ex) throws IOException {
        cors(ex);
        String p = ex.getRequestURI().getPath();
        if (p.equals("/") || p.isEmpty()) p = "/index.html";
        Path f = webroot.resolve(p.substring(1)).normalize();
        if (!f.startsWith(webroot) || !Files.isRegularFile(f)) { send(ex, 404, "text/plain", "Not found"); return; }
        String type = p.endsWith(".html") ? "text/html; charset=utf-8"
                    : p.endsWith(".css")  ? "text/css; charset=utf-8"
                    : p.endsWith(".js")   ? "text/javascript; charset=utf-8"
                    : p.endsWith(".svg")  ? "image/svg+xml"
                    : "application/octet-stream";
        byte[] bytes = Files.readAllBytes(f);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    /* --- checker --- */

    static String validate(String source) throws Exception {
        String module = find(MODULE, source);
        if (module == null) return err("No module declaration, e.g. module Foo;");

        Path dir  = Files.createTempDirectory("mmst-");
        Path file = dir.resolve(module + ".scr");
        try {
            Files.writeString(file, source, StandardCharsets.UTF_8);
            Run r = runTool(new String[]{ file.toString() });
            return report(r);
        } finally { rmrf(dir); }
    }

    static String generate(String source) throws Exception {
        String module = find(MODULE, source);
        String proto  = find(PROTOCOL, source);
        if (module == null) return err("No module declaration, e.g. module Foo;");
        if (proto  == null) return err("No global protocol.");

        Path dir  = Files.createTempDirectory("mmst-");
        Path file = dir.resolve(module + ".scr");
        Path out  = dir.resolve("out");
        try {
            Files.writeString(file, source, StandardCharsets.UTF_8);
            Run r = runTool(new String[]{ file.toString(), "-gt-generate-efsms", proto, "-out", out.toString() });
            if (r.failed()) return report(r);

            StringBuilder sb = new StringBuilder("{\"ok\":true,\"files\":[");
            if (Files.isDirectory(out)) {
                List<Path> files;
                try (Stream<Path> s = Files.walk(out, 3)) {
                    files = s.filter(Files::isRegularFile)
                             .sorted(Comparator.comparing((Path p) -> p.getFileName().toString().startsWith("gen_") ? 1 : 0)
                                               .thenComparing(p -> p.getFileName().toString()))
                             .toList();
                }
                for (int i = 0; i < files.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append("{\"name\":").append(jsonStr(files.get(i).getFileName().toString()))
                      .append(",\"content\":").append(jsonStr(Files.readString(files.get(i), StandardCharsets.UTF_8)))
                      .append('}');
                }
            }
            return sb.append("]}").toString();
        } finally { rmrf(dir); }
    }

    // exit 0: accepted.
    private record Run(String out, int exit, boolean timedOut) {
        boolean failed()   { return exit != 0 || timedOut; }
    }

    private static Run runTool(String[] argv) throws Exception {
        if (!SLOTS.tryAcquire(20, TimeUnit.SECONDS))
            return new Run("", 1, true);
        try {
            List<String> cmd = new ArrayList<>(List.of(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx" + System.getProperty("mmst.checker.heap", "256m"), "-Xss8m",
                "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC",
                "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-cp", System.getProperty("java.class.path"),
                "com.github.rhu1.gt.main.Main"));
            cmd.addAll(List.of(argv));

            Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            // Bytes, decoded once at the end (UTF-8 can straddle reads). Drain past the cap.
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = proc.getInputStream()) {
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) > 0)
                        synchronized (buf) { if (buf.size() < MAX_OUTPUT_CHARS) buf.write(chunk, 0, n); }
                } catch (IOException ignored) { }
            }, "mmst-out");
            reader.setDaemon(true);
            reader.start();

            boolean done = proc.waitFor(DEADLINE_MS, TimeUnit.MILLISECONDS);
            if (!done) {
                proc.destroyForcibly();
                proc.waitFor(2, TimeUnit.SECONDS);
                reader.join(500);
                return new Run(text(buf), 1, true);
            }
            reader.join(1000);
            return new Run(text(buf), proc.exitValue(), false);
        } finally { SLOTS.release(); }
    }

    private static String text(java.io.ByteArrayOutputStream buf) {
        synchronized (buf) { return buf.toString(StandardCharsets.UTF_8); }
    }

    /* --- efsm --- */

    private static final Pattern EFSM_HEAD = Pattern.compile("^([A-Za-z0-9_.]+)\\.([A-Za-z0-9_]+)@([A-Za-z0-9_]+):\\s*$");
    private static final Pattern EFSM_NODE = Pattern.compile("^\"([^\"]+)\" \\[ label=\"([^\"]*)\" \\];$");
    private static final Pattern EFSM_EDGE = Pattern.compile("^\"([^\"]+)\" -> \"([^\"]+)\" \\[ label=\"([^\"]*)\" \\];$");

    // -gt-print-efsm-all: one digraph per role, passed on as nodes and edges. Valid protocols only.
    static String efsm(String source) throws Exception {
        String module = find(MODULE, source);
        if (module == null) return err("No module declaration, e.g. module Foo;");

        Path dir  = Files.createTempDirectory("mmst-");
        Path file = dir.resolve(module + ".scr");
        try {
            Files.writeString(file, source, StandardCharsets.UTF_8);
            Run r = runTool(new String[]{ file.toString(), "-gt-print-efsm-all" });
            int at = r.out().indexOf("[GT] Printing GTEFSM:");
            if (r.timedOut() || at < 0) return report(r);

            StringBuilder sb = new StringBuilder("{\"ok\":true,\"machines\":[");
            StringBuilder nodes = null, edges = null;
            String proto = null, role = null;
            int machines = 0, nn = 0, ne = 0;
            for (String line : r.out().substring(at).split("\\R")) {
                line = line.strip();
                if (nodes == null) {
                    Matcher h = EFSM_HEAD.matcher(line);
                    if (h.matches()) { proto = h.group(2); role = h.group(3); }
                    else if (line.startsWith("digraph") && role != null) {
                        nodes = new StringBuilder(); edges = new StringBuilder(); nn = ne = 0;
                    }
                    continue;
                }
                if (line.equals("}")) {
                    if (machines++ > 0) sb.append(',');
                    sb.append("{\"protocol\":").append(jsonStr(proto))
                      .append(",\"role\":").append(jsonStr(role))
                      .append(",\"nodes\":[").append(nodes).append("]")
                      .append(",\"edges\":[").append(edges).append("]}");
                    nodes = edges = null; role = null;
                    continue;
                }
                Matcher e = EFSM_EDGE.matcher(line);
                if (e.matches()) {
                    if (ne++ > 0) edges.append(',');
                    edges.append("{\"from\":").append(jsonStr(e.group(1)))
                         .append(",\"to\":").append(jsonStr(e.group(2)))
                         .append(",\"label\":").append(jsonStr(e.group(3))).append('}');
                    continue;
                }
                Matcher n = EFSM_NODE.matcher(line);
                if (n.matches()) {
                    if (nn++ > 0) nodes.append(',');
                    nodes.append("{\"id\":").append(jsonStr(n.group(1)))
                         .append(",\"label\":").append(jsonStr(n.group(2))).append('}');
                }
            }
            if (machines == 0) return report(r);
            return sb.append("]}").toString();
        } finally { rmrf(dir); }
    }

    /* --- report --- */

    private static String report(Run r) {
        String out = r.out();

        if (r.timedOut())
            return err("Timed out after " + (DEADLINE_MS / 1000) + " s, or the server is busy. "
                     + "Usually deep recursion inside a mixed choice.");

        Matcher mm = MISMATCH.matcher(out);
        if (mm.find())
            return err("Module and file name differ: two module declarations?");

        if (out.contains("Inconsistent choice"))
            return err("Inconsistent choice: every branch of 'choice at X' must start with X sending. "
                     + "For a race, use 'mixed'.", "inconsistent-choice");

        Matcher proj = Pattern.compile("Couldn't project to ([A-Za-z0-9_]+)").matcher(out);
        if (proj.find())
            return err("Checks pass, but projection onto " + proj.group(1) + " fails: " + proj.group(1)
                     + " gets the same message in two branches of a choice, then acts differently. "
                     + "Use distinct labels.", "projection", out);

        if (out.contains("Cannot translate: mixed {") || out.contains("Cannot translate:"))
            return err("A mixed choice must be last in its block: put a `continue` inside the looping branch, "
                     + "not after the choice.", "after-mixed", out);

        if (out.contains("GMixed cannot be cast to") || out.contains("GInteraction"))
            return err("A mixed choice cannot be first in its block: put the opening message, between the "
                     + "pair in `or X -> Y`, before it.", "mixed-first", out);

        if (out.contains("NoSuchElementException"))
            return err("Empty block: each side of a mixed choice needs an interaction.", "empty-block");

        Boolean wf = null, sd = null, ct = null, ba = null;
        Matcher fm = FLAG.matcher(out);
        while (fm.find()) {
            boolean v = "true".equals(fm.group(2));
            switch (fm.group(1)) { case "WF" -> wf = v; case "SD" -> sd = v; case "CT" -> ct = v; case "BA" -> ba = v; }
        }

        String protocol = null;
        Matcher pm = Pattern.compile("(?m)^([A-Za-z0-9_.]+): (OK|FAIL)$").matcher(out);
        if (pm.find()) protocol = pm.group(1);

        if (wf != null) {                                   // rejected, with flags
            String culprit = find(CULPRIT, out);
            return "{\"ok\":false,\"stage\":\"validate\""
                 + ",\"protocol\":" + jsonStr(protocol)
                 + ",\"flags\":{\"WF\":" + wf + ",\"SD\":" + sd + ",\"CT\":" + ct + ",\"BA\":" + ba + "}"
                 + (culprit == null ? "" : ",\"culprit\":" + jsonStr(culprit.trim()))
                 + ",\"raw\":" + jsonStr(out) + "}";
        }

        if (r.failed())                                     // parse error or similar
            return err(parseError(out), "parse", out);

        if (out.contains(": OK")) {                         // accepted
            StringBuilder locals = new StringBuilder("[");
            Matcher lm = LOCAL.matcher(out.substring(Math.max(0, out.indexOf("[GT] Projecting:"))));
            int n = 0;
            while (lm.find()) {
                if (n++ > 0) locals.append(',');
                locals.append("{\"role\":").append(jsonStr(lm.group(2)))
                      .append(",\"type\":").append(jsonStr(lm.group(3).trim())).append('}');
            }
            return "{\"ok\":true,\"stage\":\"project\""
                 + ",\"protocol\":" + jsonStr(protocol)
                 + ",\"flags\":{\"WF\":true,\"SD\":true,\"CT\":true,\"BA\":true}"
                 + ",\"locals\":" + locals.append(']')
                 + ",\"raw\":" + jsonStr(out) + "}";
        }

        return err("No verdict in the checker's output.", null, out);
    }

    // The one actionable line.
    private static String parseError(String out) {
        Matcher m = Pattern.compile("(?m)^line (\\d+):(\\d+) (.+)$").matcher(out);
        if (m.find())
            return "Syntax error on line " + m.group(1) + ", column " + m.group(2) + ": " + m.group(3).trim();

        Matcher s = Pattern.compile("ScribException:\\s*(.+)").matcher(out);
        if (s.find()) return s.group(1).trim();

        Matcher e = Pattern.compile("(?m)^\\s*(?:Exception in thread \\S+ )?([A-Za-z0-9_.$]+(?:Exception|Error))(?:: (.*))?$").matcher(out);
        if (e.find()) {
            String detail = e.group(2) == null ? "" : ": " + e.group(2).trim();
            return "Checker error: " + e.group(1).replaceAll(".*\\.", "") + detail;
        }
        return "Parse error.";
    }

    /* --- erlang --- */

    // Erlang mode. Runs execute what they are sent: off unless switched on.
    private static final int     ERL_MAX_FILES  = 80;
    private static final int     ERL_MAX_BYTES  = 1024 * 1024;
    private static final int     ERL_MAX_OUTPUT = 300_000;
    private static final Pattern ERL_NAME       = Pattern.compile("[a-z][A-Za-z0-9_]{0,63}\\.(erl|hrl)");
    private static final Pattern ERL_PROTOCOL   = Pattern.compile("examples/scribble/([A-Za-z0-9_]+)\\.scr");

    // Concurrent runs.
    private static final java.util.concurrent.Semaphore ERL_SLOTS =
        new java.util.concurrent.Semaphore(Integer.getInteger("mmst.erlang.slots", 2));

    private static Path   erlExamples, erlGenerated;
    private static String erl;                    // null when runs are off
    private static String otp;
    private static Path   launcher;               // holds mmst_run.beam
    private static String erlOff, erlOffWhy;      // why runs are off: code, message
    private static Path   runs;                   // per-run folders go here
    private static boolean erlAll;                // runs for any client

    // Runs on? Then compile the launcher once.
    private static void erlangSetup() {
        String on = System.getProperty("mmst.erlang", "off").trim().toLowerCase(Locale.ROOT);
        erlAll = on.equals("all");
        if (!erlAll && !List.of("on", "1", "true", "yes").contains(on)) {
            erlangOff("off", "Runs are off. MMST_ERLANG=1 ./serve.sh runs them for localhost.");
            return;
        }
        String erlc = onPath("erlc");
        erl = onPath("erl");
        if (erl == null || erlc == null) {
            erlangOff("no-erlang", "No erl or erlc on the PATH.");
            return;
        }
        // Resolve version-manager shims (asdf, kerl) to the installation's own erl.
        try {
            String[] where = askErlang(erl);
            if (where != null) {
                Path bin = Paths.get(where[0], "bin");
                if (Files.isExecutable(bin.resolve("erl")))  erl  = bin.resolve("erl").toString();
                if (Files.isExecutable(bin.resolve("erlc"))) erlc = bin.resolve("erlc").toString();
                otp = where[1];
            }
        } catch (Exception ignored) { }
        Path source = folder("mmst.launcher", webroot.resolveSibling("mmst_run.erl"));
        if (source == null || !Files.isRegularFile(source)) {
            erlangOff("launcher", "No mmst_run.erl: set -Dmmst.launcher.");
            return;
        }
        try {
            Path dir = Files.createTempDirectory("mmst-launcher-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> rmrf(dir)));
            Path log = dir.resolve("erlc.txt");
            Process p = new ProcessBuilder(erlc, "-o", dir.toString(), source.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean done = p.waitFor(60, TimeUnit.SECONDS);
            if (!done) p.destroyForcibly();
            if (!done || p.exitValue() != 0 || !Files.isRegularFile(dir.resolve("mmst_run.beam"))) {
                erlangOff("launcher", "mmst_run.erl did not compile: "
                                    + safe(Files.readString(log, StandardCharsets.UTF_8)));
                return;
            }
            Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.setPosixFilePermissions(dir.resolve("mmst_run.beam"), java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
            launcher = dir;
            runs = folder("mmst.erlang.runs", Paths.get(System.getProperty("java.io.tmpdir")));
            Files.createDirectories(runs);
            jailSetup();
            if (erlOff == null) selfTest();
        } catch (Exception e) {
            erlangOff("launcher", "Launcher setup failed: " + safe(e.getMessage()));
        }
    }

    /* --- jail --- */

    // Each layer is probed at start-up and kept if it works:
    //   setpriv  own uid per run slot (server must be root); its leftovers are swept
    //   unshare  empty network namespace, own pid tree and temp dirs (needs userns)
    //   nonet    seccomp filter: no sockets but AF_UNIX (no-network fallback)
    //   prlimit  memory, open files, file size, CPU time, processes
    // -Dmmst.erlang.jail=off turns it all off.
    private static final java.util.concurrent.BlockingQueue<Integer> RUN_UIDS = new java.util.concurrent.LinkedBlockingQueue<>();
    private static final int ERL_MEMORY_MB = Integer.getInteger("mmst.erlang.memory", 768);
    private static String setprivCmd, prlimitCmd, nonetCmd, unshareCmd;   // null: layer unused
    private static boolean jailBounding;
    private static String jailed = "Runs are not confined.";

    private static void jailSetup() {
        String mode = System.getProperty("mmst.erlang.jail", "auto").trim().toLowerCase(Locale.ROOT);
        if (mode.equals("off")) { jailed = "Runs are not confined (jail=off)."; return; }
        String setpriv = onPath("setpriv"), unshare = onPath("unshare"), prlimit = onPath("prlimit");
        // A layer stays only if `true` still runs inside the stack.
        if (setpriv != null && "0".equals(output(List.of("id", "-u")))) {
            int first = Integer.getInteger("mmst.erlang.uid", 20001);
            for (int i = 0, n = ERL_SLOTS.availablePermits(); i < n; i++) RUN_UIDS.add(first + i);
            setprivCmd = setpriv;
            jailBounding = true;
            if (!jailWorks()) { jailBounding = false; if (!jailWorks()) { setprivCmd = null; RUN_UIDS.clear(); } }
        }
        if (unshare != null) { unshareCmd = unshare; if (!jailWorks()) unshareCmd = null; }
        String nonet = System.getProperty("mmst.erlang.nonet", onPath("mmst-nonet"));
        if (nonet != null && Files.isExecutable(Paths.get(nonet))) { nonetCmd = nonet; if (!jailWorks()) nonetCmd = null; }
        if (prlimit != null) { prlimitCmd = prlimit; if (!jailWorks()) prlimitCmd = null; }
        List<String> parts = new ArrayList<>();
        if (setprivCmd != null) parts.add("own user");
        if (unshareCmd != null || nonetCmd != null) parts.add("no network");
        if (unshareCmd != null) parts.add("own process tree and temp dirs");
        if (prlimitCmd != null) parts.add("limits");
        if (!parts.isEmpty())
            jailed = "Each run: " + String.join(", ", parts) + "."
                   + (unshareCmd == null && nonetCmd == null ? " Network open." : "");
        // For everyone, a run needs at least its own user and no network.
        List<String> missing = new ArrayList<>();
        if (setprivCmd == null) missing.add("no own user (needs root and setpriv)");
        if (unshareCmd == null && nonetCmd == null) missing.add("network open (needs user namespaces or mmst-nonet)");
        if (erlAll && !missing.isEmpty())
            erlangOff("unconfined", "Runs off, unconfined: " + String.join("; ", missing) + ".");
    }

    private static boolean jailWorks() {
        List<String> cmd = new ArrayList<>(jail(RUN_UIDS.peek(), 5));
        cmd.add("true");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(runs.toFile())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.environment().keySet().retainAll(Set.of("PATH"));
            Process p = pb.start();
            if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    // Wrapper: user, limits, no-net, namespaces.
    private static List<String> jail(Integer uid, int seconds) {
        List<String> c = new ArrayList<>();
        if (setprivCmd != null && uid != null) {
            c.addAll(List.of(setprivCmd, "--reuid=" + uid, "--regid=" + uid, "--clear-groups", "--no-new-privs", "--inh-caps=-all"));
            if (jailBounding) c.add("--bounding-set=-all");
        }
        if (prlimitCmd != null) {
            c.addAll(List.of(prlimitCmd, "--as=" + ((long) ERL_MEMORY_MB << 20), "--nofile=1024",
                             "--fsize=" + (64L << 20), "--core=0", "--cpu=" + (4 * seconds + 20)));
            if (setprivCmd != null && uid != null) c.add("--nproc=512");   // per user: only with an own uid
            c.add("--");
        }
        if (nonetCmd != null) c.add(nonetCmd);
        if (unshareCmd != null) {
            // Fresh tmpfs over the temp dirs, except the one holding runs; failing that, the sweep cleans up.
            StringBuilder dirs = new StringBuilder();
            for (String d : List.of("/tmp", "/var/tmp", "/dev/shm"))
                if (!runs.startsWith(d) && Files.isDirectory(Paths.get(d))) dirs.append(' ').append(d);
            c.addAll(List.of(unshareCmd, "--user", "--map-root-user", "--net", "--pid", "--fork", "--kill-child", "--mount", "--",
                             "/bin/sh", "-c", "for d in" + dirs + "; do mount -t tmpfs -o size=64m,mode=1777 tmpfs \"$d\" 2>/dev/null; done; exec \"$@\"", "sh"));
        }
        return c;
    }

    // Kill the uid's processes; delete its files in the temp dirs.
    private static void sweep(int uid) {
        try {
            Process p = new ProcessBuilder(setprivCmd, "--reuid=" + uid, "--regid=" + uid, "--clear-groups",
                                           "/bin/sh", "-c", "kill -KILL -1 2>/dev/null; exit 0")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (Exception ignored) { }
        for (String d : List.of("/tmp", "/var/tmp", "/dev/shm")) {
            Path root = Paths.get(d);
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> s = Files.walk(root, 12)) {
                s.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(root)).forEach(p -> {
                    try {
                        if (((Number) Files.getAttribute(p, "unix:uid", java.nio.file.LinkOption.NOFOLLOW_LINKS)).intValue() == uid)
                            Files.deleteIfExists(p);
                    } catch (Exception ignored) { }
                });
            } catch (Exception ignored) { }
        }
    }

    private static void own(Path p, int uid) throws IOException {
        Files.setAttribute(p, "unix:uid", uid, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        Files.setAttribute(p, "unix:gid", uid, java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    // A short command's output, trimmed; "" on failure.
    private static String output(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] b = p.getInputStream().readNBytes(4096);
            p.waitFor(10, TimeUnit.SECONDS);
            return new String(b, StandardCharsets.UTF_8).trim();
        } catch (Exception e) { return ""; }
    }

    private static void erlangOff(String code, String why) { erlOff = code; erlOffWhy = why; erl = null; }

    // Why this request may not run Erlang, or null. Same origin only (no CORS on
    // these endpoints); unless "all", also loopback, a localhost Host, no proxy.
    private static String erlangRefusal(HttpExchange ex) {
        if (erlOff != null) return erlOff;
        var h = ex.getRequestHeaders();
        String host = h.getFirst("Host"), origin = h.getFirst("Origin");
        if (host == null) return "cross-site";
        if (origin != null) {
            try {
                java.net.URI o = new java.net.URI(origin);
                String oh = o.getHost() + (o.getPort() >= 0 ? ":" + o.getPort() : "");
                if (o.getHost() == null || !oh.equalsIgnoreCase(host)) return "cross-site";
            } catch (java.net.URISyntaxException e) { return "cross-site"; }
        }
        if (erlAll) return null;
        boolean proxied = h.containsKey("X-Forwarded-For") || h.containsKey("Forwarded") || h.containsKey("X-Real-IP");
        InetSocketAddress from = ex.getRemoteAddress();
        boolean loopback = from != null && from.getAddress() != null && from.getAddress().isLoopbackAddress();
        String name = host.startsWith("[") ? host.substring(0, host.indexOf(']') + 1)
                    : host.contains(":") ? host.substring(0, host.lastIndexOf(':')) : host;
        name = name.toLowerCase(Locale.ROOT);
        boolean localName = name.equals("localhost") || name.endsWith(".localhost") || name.equals("127.0.0.1") || name.equals("[::1]");
        return loopback && localName && !proxied ? null : "local";
    }

    private static String erlangWhy(String code) {
        return switch (code) {
            case "local" -> "Runs are for localhost only. MMST_ERLANG=all opens them to everyone: containers only.";
            case "cross-site" -> "Only this playground's page can run Erlang.";
            default -> erlOffWhy;
        };
    }

    // erl's root dir and OTP release, or null.
    private static String[] askErlang(String command) throws Exception {
        Path out = Files.createTempFile("mmst-erl-", ".txt");
        try {
            Process p = new ProcessBuilder(command, "-noshell", "-eval",
                    "io:format(\"~n@@root ~s ~s~n\", [code:root_dir(), erlang:system_info(otp_release)]), halt().")
                .redirectErrorStream(true).redirectOutput(out.toFile()).start();
            if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            for (String line : Files.readAllLines(out, StandardCharsets.UTF_8))
                if (line.startsWith("@@root ")) {
                    String[] w = line.substring(7).trim().split(" ");
                    return w.length == 2 ? w : null;
                }
            return null;
        } finally { Files.deleteIfExists(out); }
    }

    private static String onPath(String command) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String d : path.split(java.io.File.pathSeparator)) {
            if (d.isEmpty()) continue;
            Path p = Paths.get(d, command);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) return p.toString();
        }
        return null;
    }

    private record ErlSet(String id, String group, String label, String path, String protocol,
                          List<Path> files, List<String> roles) { }

    // Rescanned on every request.
    private static List<ErlSet> erlangSets() {
        List<ErlSet> sets = new ArrayList<>();
        if (isDir(erlExamples)) {
            String where = shown(erlExamples.getParent()) + shown(erlExamples);
            for (Path app : subfolders(erlExamples)) {
                String name = app.getFileName().toString();
                erlangSet(sets, "examples/" + name, "examples", name, where + name + "/src",
                          protocolOf(app.resolve("README.md")), app.resolve("src"));
            }
        }
        if (isDir(erlGenerated)) {
            for (Path d : subfolders(erlGenerated)) {
                String name = d.getFileName().toString();
                erlangSet(sets, "generated/" + name, "generated", name, shown(erlGenerated) + name, name, d);
            }
        }
        return sets;
    }

    // Needs a gen_ module and must fit the limits.
    private static void erlangSet(List<ErlSet> sets, String id, String group, String label, String path,
                                  String protocol, Path dir) {
        if (!isDir(dir)) return;
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> ERL_NAME.matcher(p.getFileName().toString()).matches() && Files.isRegularFile(p))
                     .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()))
                     .toList();
        } catch (IOException e) { return; }
        long bytes = 0;
        for (Path f : files) { try { bytes += Files.size(f); } catch (IOException e) { return; } }
        List<String> roles = files.stream().map(p -> p.getFileName().toString())
            .filter(n -> n.startsWith("gen_") && n.endsWith(".erl"))
            .map(n -> n.substring(4, n.length() - 4)).toList();
        if (roles.isEmpty() || files.size() > ERL_MAX_FILES || bytes > ERL_MAX_BYTES) return;
        sets.add(new ErlSet(id, group, label, path, protocol, files, roles));
    }

    private static List<Path> subfolders(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (IOException e) { return List.of(); }
    }

    // Protocol named in the app's README.
    private static String protocolOf(Path readme) {
        try {
            if (!Files.isRegularFile(readme) || Files.size(readme) > 64 * 1024) return null;
            return find(ERL_PROTOCOL, Files.readString(readme, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) { return null; }
    }

    private static void erlangList(HttpExchange ex) throws IOException {
        String refused = erlangRefusal(ex);
        StringBuilder sb = new StringBuilder("{\"run\":").append(refused == null)
            .append(",\"code\":").append(jsonStr(refused))
            .append(",\"why\":").append(jsonStr(refused == null ? null : erlangWhy(refused)))
            .append(",\"jail\":").append(jsonStr(erlOff == null ? jailed : null))
            .append(",\"sets\":[");
        List<ErlSet> sets = erlangSets();
        for (int i = 0; i < sets.size(); i++) {
            ErlSet s = sets.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(jsonStr(s.id()))
              .append(",\"group\":").append(jsonStr(s.group()))
              .append(",\"label\":").append(jsonStr(s.label()))
              .append(",\"path\":").append(jsonStr(s.path()))
              .append(",\"protocol\":").append(jsonStr(s.protocol()))
              .append(",\"roles\":[");
            for (int k = 0; k < s.roles().size(); k++) sb.append(k > 0 ? "," : "").append(jsonStr(s.roles().get(k)));
            sb.append("],\"files\":").append(s.files().size()).append('}');
        }
        send(ex, 200, "application/json", sb.append("]}").toString());
    }

    private static void erlangFiles(HttpExchange ex) throws IOException {
        String q = ex.getRequestURI().getRawQuery(), id = null;
        if (q != null)
            for (String kv : q.split("&"))
                if (kv.startsWith("id=")) id = java.net.URLDecoder.decode(kv.substring(3), StandardCharsets.UTF_8);
        for (ErlSet s : erlangSets()) {
            if (!s.id().equals(id)) continue;
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"id\":").append(jsonStr(s.id()))
                .append(",\"group\":").append(jsonStr(s.group()))
                .append(",\"label\":").append(jsonStr(s.label()))
                .append(",\"path\":").append(jsonStr(s.path()))
                .append(",\"protocol\":").append(jsonStr(s.protocol()))
                .append(",\"files\":[");
            for (int i = 0; i < s.files().size(); i++) {
                Path f = s.files().get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"name\":").append(jsonStr(f.getFileName().toString()))
                  .append(",\"content\":").append(jsonStr(new String(Files.readAllBytes(f), StandardCharsets.UTF_8))).append('}');
            }
            send(ex, 200, "application/json", sb.append("]}").toString());
            return;
        }
        send(ex, 200, "application/json", err("No Erlang set " + (id == null ? "given" : id) + "."));
    }

    private static void erlangRun(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "application/json", err("Use POST.")); return; }
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            send(ex, 415, "application/json", err("Send JSON."));     // other sites can't without a preflight
            return;
        }
        String refused = erlangRefusal(ex);
        if (refused != null) {
            send(ex, refused.equals("cross-site") ? 403 : 200, "application/json",
                 "{\"ok\":false,\"stage\":\"off\",\"code\":" + jsonStr(refused) + ",\"error\":" + jsonStr(erlangWhy(refused)) + "}");
            return;
        }
        // Escaping can double the size; larger bodies are refused unread.
        byte[] raw = readAtMost(ex.getRequestBody(), 2 * ERL_MAX_BYTES + 64 * 1024);
        String out;
        try {
            out = raw == null ? err("Over 1 MB.")
                              : runErlang(Json.parse(new String(raw, StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException e) {
            out = err("Bad request: " + safe(e.getMessage()));
        } catch (Exception | StackOverflowError e) {
            out = err("Run failed: " + safe(String.valueOf(e.getMessage())));
        }
        send(ex, 200, "application/json", out);
    }

    private static byte[] readAtMost(InputStream in, int limit) throws IOException {
        byte[] b = in.readNBytes(limit + 1);
        return b.length > limit ? null : b;
    }

    static String runErlang(Object request) throws Exception {
        if (!(request instanceof java.util.Map<?, ?> req) || !(req.get("files") instanceof List<?> list))
            return err("Send {\"files\":[{\"name\":\"...\",\"content\":\"...\"}]}.");
        if (list.isEmpty()) return err("No files.");
        if (list.size() > ERL_MAX_FILES) return err("Over " + ERL_MAX_FILES + " files.");
        java.util.LinkedHashMap<String, String> files = new java.util.LinkedHashMap<>();
        long bytes = 0;
        for (Object o : list) {
            if (!(o instanceof java.util.Map<?, ?> f) || !(f.get("name") instanceof String name)
                || !(f.get("content") instanceof String content))
                return err("Each file needs a name and content.");
            if (!ERL_NAME.matcher(name).matches())
                return err("Bad file name \"" + safe(name) + "\": lower-case letter first; letters, digits, _; .erl or .hrl.");
            if (name.equals("mmst_run.erl")) return err("mmst_run is reserved.");
            if (files.put(name, content) != null) return err("Two files called " + name + ".");
            bytes += content.getBytes(StandardCharsets.UTF_8).length;
        }
        if (bytes > ERL_MAX_BYTES) return err("Over 1 MB.");
        int seconds = req.get("seconds") instanceof Number n ? Math.max(1, Math.min(10, n.intValue())) : 3;

        if (!ERL_SLOTS.tryAcquire(15, TimeUnit.SECONDS))
            return "{\"ok\":false,\"stage\":\"busy\",\"error\":" + jsonStr("Busy. Try again in a few seconds.") + "}";
        Integer uid = RUN_UIDS.poll();                   // null unless runs get their own user
        Path dir = null;
        try {
            dir = Files.createTempDirectory(runs, "mmst-erl-");
            Path src = Files.createDirectory(dir.resolve("src")), lib = Files.createDirectory(dir.resolve("launcher"));
            Files.copy(launcher.resolve("mmst_run.beam"), lib.resolve("mmst_run.beam"));
            for (var e : files.entrySet()) Files.writeString(src.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
            if (uid != null)
                try (Stream<Path> s = Files.walk(dir)) { for (Path p : s.toList()) own(p, uid); }
            return erlangReport(runLauncher(dir, src, lib, seconds, uid), seconds);
        } finally {
            if (uid != null) { sweep(uid); RUN_UIDS.add(uid); }
            if (dir != null) rmrf(dir);
            ERL_SLOTS.release();
        }
    }

    private record ErlRun(String out, String tag, boolean timedOut, boolean truncated) { }

    // erl under the jail, in dir, with a clean environment, then `run`.
    private static ProcessBuilder vm(Path dir, Integer uid, int seconds, List<String> run) {
        List<String> cmd = new ArrayList<>(jail(uid, seconds));
        cmd.add(erl);
        if (prlimitCmd != null) cmd.addAll(List.of("+MIscs", "32"));   // smaller literal area, to fit --as
        // At most 4 schedulers: one per host core would not fit the memory limit.
        String cpus = String.valueOf(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
        cmd.addAll(List.of("+S", cpus + ":" + cpus, "+SDcpu", cpus + ":" + cpus, "+SDio", "4"));
        cmd.addAll(List.of("-noshell", "-noinput", "-nostick", "+P", "16384"));
        cmd.addAll(run);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        java.util.Map<String, String> env = pb.environment();
        env.keySet().retainAll(Set.of("PATH"));
        env.put("HOME", dir.toString());                 // no ~/.erlang, no cookie
        env.put("LANG", "C.UTF-8");
        env.put("ERL_CRASH_DUMP_SECONDS", "0");
        env.put("MALLOC_ARENA_MAX", "2");                // each glibc arena reserves 64 MB: too much for --as
        return pb;
    }

    // One trivial run at start-up. If it fails, every run would: runs off.
    private static void selfTest() {
        String why;
        try {
            String r = runErlang(java.util.Map.of("seconds", 1, "files", List.of(java.util.Map.of(
                "name", "mmst_selftest.erl",
                "content", "-module(mmst_selftest).\n-export([f/0]).\nf() -> [X * X || X <- lists:seq(1, 9)].\n"))));
            if (r.contains("\"stage\":\"run\"")) return;
            why = r;
            if (Json.parse(r) instanceof java.util.Map<?, ?> m) {
                why = m.get("error") instanceof String e ? e : "stage " + m.get("stage");
                if (m.get("output") instanceof String o && !o.isBlank()) why += " Output: " + safe(o.length() > 300 ? o.substring(0, 300) : o);
            }
        } catch (Exception e) {
            why = safe(String.valueOf(e.getMessage()));
        }
        String msg = "Runs fail in the jail: " + why;
        if (!msg.matches("(?s).*[.!?]$")) msg += ".";
        erlangOff("vm", msg + (prlimitCmd != null ? " Memory limit " + ERL_MEMORY_MB + " MB (MMST_ERLANG_MEMORY)." : ""));
    }

    private static ErlRun runLauncher(Path dir, Path src, Path lib, int seconds, Integer uid) throws Exception {
        String tag = Long.toHexString(new java.security.SecureRandom().nextLong() | 1L << 62);
        ProcessBuilder pb = vm(dir, uid, seconds, List.of(
            "-pa", lib.toString(), "-run", "mmst_run", "main", String.valueOf(seconds * 1000), src.toString(), tag));
        Process proc = pb.start();
        Output buf = new Output(ERL_MAX_OUTPUT, 64 * 1024);
        Thread reader = new Thread(() -> {
            try (InputStream in = proc.getInputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, n);
            } catch (IOException ignored) { }
        }, "mmst-erl-out");
        reader.setDaemon(true);
        reader.start();
        // Backstop: the launcher stops itself at the deadline.
        boolean done = proc.waitFor(seconds * 1000L + 20_000, TimeUnit.MILLISECONDS);
        proc.descendants().forEach(ProcessHandle::destroyForcibly);
        if (!done) { proc.destroyForcibly(); proc.waitFor(2, TimeUnit.SECONDS); }
        reader.join(1000);
        return new ErlRun(buf.text(), tag, !done, buf.cut());
    }

    // Run output: the head up to a cap, and a ring of the tail, where the verdict is.
    private static final class Output {
        private final byte[] head, tail;
        private int headLen;
        private long total;

        Output(int headCap, int tailCap) { head = new byte[headCap]; tail = new byte[tailCap]; }

        synchronized void write(byte[] b, int n) {
            int off = 0;
            if (headLen < head.length) {
                int k = Math.min(n, head.length - headLen);
                System.arraycopy(b, 0, head, headLen, k);
                headLen += k;
                off = k;
                total += k;
            }
            int m = n - off;
            if (m <= 0) return;
            long pos = total - headLen;                    // bytes past the head
            if (m > tail.length) { off += m - tail.length; pos += m - tail.length; total += m - tail.length; m = tail.length; }
            int start = (int) (pos % tail.length), first = Math.min(m, tail.length - start);
            System.arraycopy(b, off, tail, start, first);
            System.arraycopy(b, off + first, tail, 0, m - first);
            total += m;
        }

        synchronized boolean cut() { return total - headLen > tail.length; }

        synchronized String text() {
            long past = total - headLen;
            String h = new String(head, 0, headLen, StandardCharsets.UTF_8);
            if (past == 0) return h;
            int keep = (int) Math.min(past, tail.length), end = (int) (past % tail.length);
            byte[] t = new byte[keep];
            int start = Math.floorMod(end - keep, tail.length), first = Math.min(keep, tail.length - start);
            System.arraycopy(tail, start, t, 0, first);
            System.arraycopy(tail, 0, t, first, keep - first);
            String gap = past > keep ? "\n[… " + ((past - keep) / 1024) + " KB cut …]\n" : "";
            return h + gap + new String(t, StandardCharsets.UTF_8);
        }
    }

    // Launcher directives (@@tag lines) apart from role output; to JSON.
    private static String erlangReport(ErlRun r, int seconds) {
        String mark = "@@" + r.tag() + " ";
        StringBuilder log = new StringBuilder(), errors = new StringBuilder(), warnings = new StringBuilder(),
                      roles = new StringBuilder(), renamed = new StringBuilder(), clash = new StringBuilder();
        int nErrors = 0, nWarnings = 0, nRoles = 0, compiled = -1;
        long compileMs = 0, runMs = 0;
        boolean done = false, nothing = false, failed = false;
        java.util.Map<String, String> modules = new java.util.HashMap<>();
        List<String[]> outcomes = new ArrayList<>();
        for (String line : r.out().split("\n", -1)) {
            int k = line.indexOf(mark);
            if (k < 0) { log.append(line).append('\n'); continue; }
            if (k > 0) log.append(line, 0, k).append('\n');
            String d = line.substring(k + mark.length()).replace("\r", "");
            String[] w = d.split(" ", 2);
            String rest = w.length > 1 ? w[1] : "";
            switch (w[0]) {
                case "done"     -> done = true;
                case "as"       -> { String[] c = rest.split(" "); if (c.length == 2) modules.put(c[0], c[1]); }
                case "started"  -> { }
                case "nothing"  -> nothing = true;
                case "ran"      -> runMs = parseLong(rest);
                case "compiled" -> { String[] c = rest.split(" "); compiled = (int) parseLong(c[0]); compileMs = c.length > 1 ? parseLong(c[1]) : 0; }
                case "failed"   -> { failed = true; compileMs = parseLong(rest); }
                case "renamed"  -> { String[] c = rest.split(" ");
                                     if (c.length == 2) (renamed.length() > 0 ? renamed.append(',') : renamed)
                                         .append("{\"from\":").append(jsonStr(c[0])).append(",\"to\":").append(jsonStr(c[1])).append('}'); }
                case "clash"    -> { for (String m : rest.trim().split(" +"))
                                         if (!m.isEmpty()) (clash.length() > 0 ? clash.append(',') : clash).append(jsonStr(m)); }
                case "error", "warning" -> {
                    String[] f = rest.split("\t", 4);
                    if (f.length < 4) break;
                    StringBuilder into = w[0].equals("error") ? errors : warnings;
                    int count = w[0].equals("error") ? nErrors++ : nWarnings++;
                    if (count >= 500) break;
                    if (count > 0) into.append(',');
                    into.append("{\"file\":").append(jsonStr(f[0]))
                        .append(",\"line\":").append(parseLong(f[1]))
                        .append(",\"col\":").append(parseLong(f[2]))
                        .append(",\"text\":").append(jsonStr(f[3])).append('}');
                }
                case "role" -> { String[] f = rest.split(" ", 3); if (f.length >= 2) outcomes.add(f); }
                default -> log.append(line).append('\n');
            }
        }
        while (log.length() > 0 && log.charAt(log.length() - 1) == '\n') log.setLength(log.length() - 1);
        for (String[] f : outcomes) {
            if (nRoles++ > 0) roles.append(',');
            roles.append("{\"name\":").append(jsonStr(f[0])).append(",\"status\":").append(jsonStr(f[1]))
                 .append(",\"module\":").append(jsonStr(modules.get(f[0])));
            String detail = f.length > 2 ? f[2] : "";
            if (f[1].equals("waiting")) {
                String[] s = detail.split("\t");
                roles.append(",\"state\":").append(jsonStr(s[0]))
                     .append(",\"queue\":").append(s.length > 1 ? parseLong(s[1]) : 0)
                     .append(",\"postponed\":").append(s.length > 2 ? parseLong(s[2]) : 0);
            } else if (!detail.isEmpty()) {
                roles.append(",\"detail\":").append(jsonStr(detail));
            }
            roles.append('}');
        }

        String stage, error = null;
        if (clash.length() > 0) {
            stage = "clash";
            error = "Module name taken by the runtime (often a role called user or logger): rename the role.";
        } else if (failed || nErrors > 0) {
            stage = "compile";
        } else if (r.timedOut()) {
            stage = "error";
            error = "Did not stop; killed.";
        } else if (!done) {
            stage = "error";
            error = log.indexOf("Cannot allocate") >= 0
                ? "Out of memory (" + ERL_MEMORY_MB + " MB limit)."
                : "VM stopped early: halt(), killed, or out of CPU time.";
        } else {
            stage = "run";
        }
        return "{\"ok\":" + stage.equals("run")
             + ",\"stage\":" + jsonStr(stage)
             + (error == null ? "" : ",\"error\":" + jsonStr(error))
             + ",\"compiled\":" + Math.max(0, compiled)
             + ",\"compileMs\":" + compileMs + ",\"runMs\":" + runMs + ",\"seconds\":" + seconds
             + ",\"nothing\":" + nothing
             + ",\"renamed\":[" + renamed + "],\"clash\":[" + clash + "]"
             + ",\"errors\":[" + errors + "],\"warnings\":[" + warnings + "],\"moreWarnings\":" + Math.max(0, nWarnings - 500)
             + ",\"roles\":[" + roles + "]"
             + ",\"truncated\":" + r.truncated()
             + ",\"output\":" + jsonStr(log.toString()) + "}";
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    // Minimal JSON reader: maps, lists, strings, doubles, booleans, null.
    static final class Json {
        private final String s;
        private int i;
        private Json(String s) { this.s = s; }

        static Object parse(String s) {
            Json j = new Json(s);
            Object v = j.value();
            j.space();
            if (j.i != s.length()) throw new IllegalArgumentException("text after the JSON");
            return v;
        }

        private void space() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private char next() {
            space();
            if (i >= s.length()) throw new IllegalArgumentException("JSON ends early");
            return s.charAt(i);
        }

        private void expect(char c) {
            if (next() != c) throw new IllegalArgumentException("expected " + c + " at " + i);
            i++;
        }

        private Object value() {
            char c = next();
            if (c == '{') {
                i++;
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                if (next() == '}') { i++; return m; }
                while (true) {
                    if (next() != '"') throw new IllegalArgumentException("expected a key at " + i);
                    String k = string();
                    expect(':');
                    m.put(k, value());
                    if (next() == ',') { i++; continue; }
                    expect('}');
                    return m;
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                if (next() == ']') { i++; return l; }
                while (true) {
                    l.add(value());
                    if (next() == ',') { i++; continue; }
                    expect(']');
                    return l;
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i))  { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i))  { i += 4; return null; }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            try { return Double.valueOf(s.substring(start, i)); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("unexpected " + c + " at " + start); }
        }

        private String string() {
            i++;                                            // opening quote
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n'); case 't' -> sb.append('\t'); case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b'); case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw new IllegalArgumentException("bad \\u escape");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> sb.append(e);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }
    }

    /* --- plumbing --- */

    private static String err(String message)                    { return err(message, null, null); }
    private static String err(String message, String code)       { return err(message, code, null); }
    private static String err(String message, String code, String raw) {
        return "{\"ok\":false,\"stage\":\"error\",\"error\":" + jsonStr(message)
             + (code == null ? "" : ",\"code\":" + jsonStr(code))
             + (raw  == null ? "" : ",\"raw\":"  + jsonStr(raw)) + "}";
    }

    private static String find(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String safe(String s) { return s == null ? "" : s.replaceAll("[\\r\\n]+", " ").trim(); }

    // One string field from a small JSON body.
    private static String jsonGet(String body, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"").matcher(body);
        if (!m.find()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = m.end(); i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char n = body.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n'); case 't' -> sb.append('\t'); case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b'); case 'f' -> sb.append('\f');
                    case 'u' -> { sb.append((char) Integer.parseInt(body.substring(i + 1, i + 5), 16)); i += 4; }
                    default  -> sb.append(n);
                }
            } else if (c == '"') return sb.toString();
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> { if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.append('"').toString();
    }

    private static void cors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static void rmrf(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }

    private Playground() { }
}
