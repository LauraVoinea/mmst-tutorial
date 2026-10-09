import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.github.rhu1.gt.main.Main;
import com.github.rhu1.gt.type.session.Role;
import com.github.rhu1.gt.type.session.global.GEnd$;
import com.github.rhu1.gt.type.session.global.GInteraction;
import com.github.rhu1.gt.type.session.global.GMixed;
import com.github.rhu1.gt.type.session.global.GRec;
import com.github.rhu1.gt.type.session.global.GRecVar;
import com.github.rhu1.gt.type.session.global.GType;
import com.github.rhu1.gt.type.session.global.Scrib2GT;
import com.github.rhu1.gt.type.session.local.LBranch;
import com.github.rhu1.gt.type.session.local.LEnd$;
import com.github.rhu1.gt.type.session.local.LMixed;
import com.github.rhu1.gt.type.session.local.LSelect;
import org.antlr.runtime.ANTLRStringStream;
import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.tree.CommonTree;
import org.scribble.ast.SigLitNode;
import org.scribble.ast.global.GChoice;
import org.scribble.ast.global.GContinue;
import org.scribble.ast.global.GInteractionSeq;
import org.scribble.ast.global.GMsgTransfer;
import org.scribble.ast.global.GProtoBlock;
import org.scribble.ast.global.GProtoDecl;
import org.scribble.ast.global.GRecursion;
import org.scribble.ast.global.GSessionNode;
import org.scribble.ext.gt.ast.global.GTGMixed;
import org.scribble.ext.gt.cli.GTCommandLine2;
import org.scribble.parser.antlr.GTScribbleLexer;
import scala.jdk.javaapi.CollectionConverters;

/*
 * The checker's Main, and where in the source it fails.
 *
 *   java -cp <classes:checker> [-Dmmst.problems=FILE] Diagnose <file.scr> [checker options]
 *
 * Runs Main. If Main throws: writes the problems to FILE as a JSON array (no FILE:
 * stdout, an "@@mmst {...}" line each), then rethrows, so output and exit code are Main's.
 * A problem: kind, protocol, line, head (the construct), text, and lines to mark, soft and hard.
 *
 *   WF SD CT BA     per mixed choice, BA also per choice: the checker's own conditions,
 *                   on its own types
 *   projection      the innermost choice or mixed choice that does not project
 *   translate, first, choice, mixed-roles, empty
 *                   what Scrib2GT rejects, found in the syntax tree
 *
 * Lines: a re-parse, and a mirror of Scrib2GT that keeps each message's node, used only
 * where it matches the checker's types. If anything here fails, Main's output stands.
 */
public final class Diagnose {

    public static void main(String[] args) throws Throwable {
        try {
            Main.main(args);
        } catch (Throwable t) {
            PrintStream out = System.out;
            try { new Diagnose(args[0], out).explain(t); }
            catch (Throwable ignored) { }
            finally { System.setOut(out); out.flush(); }
            throw t;
        }
    }

    private static final PrintStream NULL = new PrintStream(OutputStream.nullOutputStream());
    private static final scala.collection.immutable.Set<Role> NONE = scala.collection.immutable.Set$.MODULE$.empty();
    @SuppressWarnings({"rawtypes"})
    private static final scala.collection.immutable.List EPSILON = scala.collection.immutable.Nil$.MODULE$;

    private final String file;
    private final PrintStream out;
    private CommonTokenStream tokens;
    private final Map<String, P> found = new LinkedHashMap<>();
    private Field fId, fLeft, fObs, fRight;

    private Diagnose(String file, PrintStream out) { this.file = file; this.out = out; }

    private void explain(Throwable t) throws Exception {
        String why = String.valueOf(t.getMessage());
        String invalid = why.startsWith("Invalid: ") ? why.substring(9).trim() : null;
        String role = why.startsWith("Couldn't project to ") ? why.substring(20).replaceAll(":.*", "").trim() : null;
        boolean shape = why.startsWith("Cannot translate") || why.startsWith("Inconsistent choice")
                     || why.startsWith("Inconsistent mixed roles")
                     || t instanceof ClassCastException || t instanceof java.util.NoSuchElementException;
        if (invalid == null && role == null && !shape) return;

        fId = field("id"); fLeft = field("left"); fObs = field("obs"); fRight = field("right");
        String text = new String(Files.readAllBytes(Paths.get(file)));      // as Scribble reads it
        tokens = new CommonTokenStream(new GTScribbleLexer(new ANTLRStringStream(text)));
        tokens.fill();

        System.setOut(NULL);                                                 // the parser and checks print
        Map<?, ?> parsed = new GTCommandLine2("-fair", "-v", file).gtMain();
        for (Object v : parsed.values()) {
            org.scribble.ast.Module m = (org.scribble.ast.Module) v;
            for (GProtoDecl p : m.getGProtoDeclChildren()) {
                String full = p.getFullMemberName(m).toString();
                String name = p.getFullMemberName(m).getSimpleName().toString();
                GInteractionSeq body = p.getDefChild().getBlockChild().getInteractSeqChild();
                try {
                    if (invalid != null) { if (full.equals(invalid)) validity(name, body); }
                    else if (role != null) projection(name, body, role);
                    else structure(name, p, body);
                } catch (Exception | LinkageError e) { /* no detail for this protocol */ }
            }
        }
        System.setOut(out);
        emit();
    }

    /* --- problems --- */

    private static final class P {
        final String kind, protocol, head;
        final int line;
        final Set<String> text = new java.util.LinkedHashSet<>();
        final TreeSet<Integer> soft = new TreeSet<>(), hard = new TreeSet<>();
        P(String kind, String protocol, int line, String head) {
            this.kind = kind; this.protocol = protocol; this.line = line; this.head = head;
        }
    }

    // One per construct and kind; its text gathers.
    private P problem(String kind, String protocol, int line, String head) {
        return found.computeIfAbsent(kind + "\u0000" + protocol + "\u0000" + line + "\u0000" + head,
                                     k -> new P(kind, protocol, line, head));
    }

    private P mixedProblem(String kind, String protocol, GTGMixed x) {
        P p = problem(kind, protocol, first(x), orHead(x));
        p.soft.add(first(x));
        p.soft.add(first(x.getOtherChild()));
        return p;
    }

    private P choiceProblem(String kind, String protocol, GChoice c) {
        P p = problem(kind, protocol, first(c), "choice at " + c.getSubjectChild());
        p.soft.add(first(c));
        return p;
    }

    private static String orHead(GTGMixed x) { return "or " + x.getOtherChild() + " -> " + x.getObserverChild(); }

    private static final List<String> ORDER = List.of("WF", "SD", "CT", "BA", "projection",
                                                      "translate", "first", "choice", "mixed-roles", "empty");

    private void emit() throws Exception {
        List<P> ps = new ArrayList<>(found.values());
        ps.sort(java.util.Comparator.comparingInt((P p) -> ORDER.indexOf(p.kind)).thenComparingInt(p -> p.line));
        StringBuilder all = new StringBuilder("[");
        for (P p : ps) {
            if (p.text.isEmpty()) continue;
            String json = "{\"kind\":" + str(p.kind) + ",\"protocol\":" + str(p.protocol) + ",\"line\":" + p.line
                        + ",\"head\":" + str(p.head) + ",\"text\":" + str(String.join("; ", p.text))
                        + ",\"soft\":" + p.soft + ",\"hard\":" + p.hard + "}";
            all.append(all.length() > 1 ? "," : "").append(json);
            if (System.getProperty("mmst.problems") == null) out.println("@@mmst " + json);
        }
        String to = System.getProperty("mmst.problems");
        if (to != null) Files.writeString(Paths.get(to), all.append(']'), StandardCharsets.UTF_8);
    }

    private static String str(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    // "line 9", "lines 9, 14"
    private static String lines(Set<Integer> ls) {
        StringBuilder b = new StringBuilder(ls.size() == 1 ? "line " : "lines ");
        int i = 0;
        for (int n : ls) b.append(i++ > 0 ? ", " : "").append(n);
        return b.toString();
    }

    private static String names(Set<String> s) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        for (String x : s) b.append(i++ > 0 ? ", " : "").append('`').append(x).append('`');
        return b.toString();
    }

    /* --- positions: token indexes into a re-lex of the source --- */

    private int first(CommonTree n) {
        int i = n.getTokenStartIndex();
        return i >= 0 && i < tokens.size() ? tokens.get(i).getLine() : n.getLine();
    }

    // Lines of the messages in a block that involve any of the roles.
    private void involving(CommonTree block, Set<String> roles, Set<Integer> into) {
        if (block instanceof GMsgTransfer m) {
            if (roles.contains(m.getSourceChild().toString()) || roles.contains(m.getDestinationChildren().get(0).toString()))
                into.add(first(m));
            return;
        }
        for (int i = 0; i < block.getChildCount(); i++)
            if (block.getChild(i) instanceof CommonTree c) involving(c, roles, into);
    }

    /* --- WF, SD, CT, BA --- */

    private void validity(String proto, GInteractionSeq body) throws Exception {
        GType g = Scrib2GT.translateSeq(body);
        int base = Integer.MAX_VALUE;
        for (Object o : CollectionConverters.asJava(g.getMids())) base = Math.min(base, (Integer) o);
        T mirror = new Mirror(base == Integer.MAX_VALUE ? 0 : base - 1).seq(body);
        Map<Integer, X> byId = new HashMap<>();
        mixedChoices(mirror, byId);
        if (!sameIds(g, byId)) return;

        boolean wf = g.isWellFormed(), sd = g.isSingleDecision(), ct = g.isClearTermination(), ba = g.isBalanced();
        if (!wf) wellFormed(proto, g, mirror, byId);
        if (!sd || !ct) decisions(proto, g, byId, !sd, !ct);
        if (!ba) balance(proto, g.unfoldAllOnce(), once(mirror, Set.of()), byId);
    }

    // The mirror's mixed choices are the checker's: same ids, same observer.
    private boolean sameIds(GType g, Map<Integer, X> byId) throws Exception {
        Map<Integer, String> ids = new HashMap<>();
        checkerIds(g, ids);
        if (!ids.keySet().equals(byId.keySet())) return false;
        for (var e : ids.entrySet()) if (!e.getValue().equals(byId.get(e.getKey()).obs)) return false;
        return true;
    }

    private void checkerIds(GType g, Map<Integer, String> ids) throws Exception {
        if (g instanceof GMixed m) {
            ids.put(fId.getInt(m), fObs.get(m).toString());
            checkerIds(left(m), ids);
            checkerIds(right(m), ids);
        } else if (g instanceof GInteraction i) {
            for (GType n : next(i)) checkerIds(n, ids);
        } else if (g instanceof GRec r) {
            checkerIds(r.body(), ids);
        }
    }

    // Per mixed choice: labels both committing and not, by role (Definition 3.4).
    private void wellFormed(String proto, GType g, T mirror, Map<Integer, X> byId) throws Exception {
        GType u = g.unfoldAllOnce();
        T mu = once(mirror, Set.of());
        for (int c : new TreeSet<>(byId.keySet())) {
            Map<String, TreeSet<String>> com = ops(u.getCommittingAux(false, c, NONE));
            Map<String, TreeSet<String>> non = ops(u.getNotCommittingAux(false, c, NONE));
            Map<String, TreeSet<String>> both = new TreeMap<>();
            for (var e : com.entrySet()) {
                TreeSet<String> s = new TreeSet<>(e.getValue());
                s.retainAll(non.getOrDefault(e.getKey(), new TreeSet<>()));
                if (!s.isEmpty()) both.put(e.getKey(), s);
            }
            if (both.isEmpty()) continue;

            Labels yes = new Labels(), no = new Labels();
            committing(mu, false, c, Set.of(), yes);
            notCommitting(mu, false, c, Set.of(), no);
            boolean exact = yes.ops().equals(com) && no.ops().equals(non);

            P p = mixedProblem("WF", proto, byId.get(c).at);
            for (var e : both.entrySet())
                for (String op : e.getValue()) {
                    String what = "`" + op + "` to `" + e.getKey() + "`";
                    if (!exact) { p.text.add(what + ": committing and not"); continue; }
                    TreeSet<Integer> y = yes.lines(e.getKey(), op), n = no.lines(e.getKey(), op);
                    TreeSet<Integer> again = new TreeSet<>(y);              // via a loop, the same line both ways
                    again.retainAll(n);
                    if (!again.isEmpty() && y.equals(n))
                        p.text.add(what + " on " + lines(y) + ": committing the first time, not when it loops");
                    else
                        p.text.add(what + ": committing on " + lines(y) + ", not on " + lines(n)
                                   + (again.isEmpty() ? "" : " (" + again.toString().replaceAll("[\\[\\]]", "") + " when it loops)"));
                    p.hard.addAll(y);
                    p.hard.addAll(n);
                }
        }
    }

    private static Map<String, TreeSet<String>> ops(scala.collection.immutable.Map<Role, scala.collection.immutable.Set<com.github.rhu1.gt.type.session.Op>> m) {
        Map<String, TreeSet<String>> r = new TreeMap<>();
        for (var e : CollectionConverters.asJava(m).entrySet()) {
            TreeSet<String> s = new TreeSet<>();
            for (Object o : CollectionConverters.asJava(e.getValue())) s.add(o.toString());
            if (!s.isEmpty()) r.put(e.getKey().toString(), s);
        }
        return r;
    }

    // SD: on the right, every role but the observer depends on it. CT: on the left, likewise,
    // eventually, unless it diverges. The checker's conditions, at each mixed choice.
    private void decisions(String proto, GType g, Map<Integer, X> byId, boolean sd, boolean ct) throws Exception {
        Map<Integer, TreeSet<String>> sdOut = new TreeMap<>(), ctOut = new TreeMap<>();
        decisionsAt(g, sd, ct, sdOut, ctOut);
        for (var e : sdOut.entrySet()) {
            X x = byId.get(e.getKey());
            P p = mixedProblem("SD", proto, x.at);
            p.text.add("on the right, `" + x.obs + "` never reaches " + names(e.getValue()));
            involving(x.at.getRightBlockChild(), e.getValue(), p.hard);
        }
        for (var e : ctOut.entrySet()) {
            X x = byId.get(e.getKey());
            P p = mixedProblem("CT", proto, x.at);
            p.text.add("on the left, `" + x.obs + "` never reaches " + names(e.getValue()));
            involving(x.at.getLeftBlockChild(), e.getValue(), p.hard);
        }
    }

    private void decisionsAt(GType g, boolean sd, boolean ct, Map<Integer, TreeSet<String>> sdOut,
                             Map<Integer, TreeSet<String>> ctOut) throws Exception {
        if (g instanceof GMixed m) {
            int id = fId.getInt(m);
            Role obs = (Role) fObs.get(m);
            GInteraction l = left(m), r = right(m);
            Set<Role> others = new HashSet<>(CollectionConverters.asJava(m.getLiveRoles()));
            others.remove(obs);
            if (sd) {
                Map<Role, scala.collection.immutable.Set<Role>> dr = new HashMap<>(CollectionConverters.asJava(r.getSyntacticStrictDeps()));
                dr.remove(obs);
                Set<Role> bad = new HashSet<>();
                for (Role x : others) if (!dr.containsKey(x) || !dr.get(x).contains(obs)) bad.add(x);
                for (var e : dr.entrySet()) if (!e.getValue().contains(obs)) bad.add(e.getKey());
                for (Role x : bad) sdOut.computeIfAbsent(id, k -> new TreeSet<>()).add(x.toString());
            }
            if (ct) {
                Map<Role, scala.collection.immutable.Set<Role>> dl = new HashMap<>(CollectionConverters.asJava(l.getSyntacticEventualDeps()));
                dl.remove(obs);
                for (Role x : others)
                    if (!l.isDiverging(x) && !(dl.containsKey(x) && dl.get(x).contains(obs)))
                        ctOut.computeIfAbsent(id, k -> new TreeSet<>()).add(x.toString());
            }
            decisionsAt(l, sd, ct, sdOut, ctOut);
            decisionsAt(r, sd, ct, sdOut, ctOut);
        } else if (g instanceof GInteraction i) {
            for (GType n : next(i)) decisionsAt(n, sd, ct, sdOut, ctOut);
        } else if (g instanceof GRec r) {
            decisionsAt(r.body(), sd, ct, sdOut, ctOut);
        }
    }

    // BA, on the unfolded types: the sides of a mixed choice, and the branches of a choice,
    // involve the same roles. Choices need the mirror, in step.
    private void balance(String proto, GType u, T mu, Map<Integer, X> byId) throws Exception {
        boolean inStep = same(u, mu);
        Map<Integer, TreeSet<String>[]> sides = new TreeMap<>();
        Map<GChoice, TreeMap<String, TreeSet<String>>> branches = new LinkedHashMap<>();
        balanceAt(u, inStep ? mu : null, sides, branches);
        for (var e : sides.entrySet()) {
            X x = byId.get(e.getKey());
            P p = mixedProblem("BA", proto, x.at);
            TreeSet<String> l = e.getValue()[0], r = e.getValue()[1];
            if (!l.isEmpty()) p.text.add(names(l) + " only on the left");
            if (!r.isEmpty()) p.text.add(names(r) + " only on the right");
            involving(x.at.getLeftBlockChild(), l, p.hard);
            involving(x.at.getRightBlockChild(), r, p.hard);
        }
        for (var e : branches.entrySet()) {
            P p = choiceProblem("BA", proto, e.getKey());
            for (var r : e.getValue().entrySet()) {
                StringBuilder after = new StringBuilder();
                for (String op : r.getValue()) after.append(after.length() > 0 ? ", " : "").append('`').append(op).append('`');
                p.text.add("`" + r.getKey() + "` only after " + after);
            }
            for (GProtoBlock b : e.getKey().getBlockChildren()) involving(b, e.getValue().keySet(), p.hard);
        }
    }

    @SuppressWarnings("unchecked")
    private void balanceAt(GType g, T t, Map<Integer, TreeSet<String>[]> sides,
                           Map<GChoice, TreeMap<String, TreeSet<String>>> branches) throws Exception {
        if (g instanceof GMixed m) {
            Set<String> l = names(left(m).getLiveRoles()), r = names(right(m).getLiveRoles());
            if (!l.equals(r)) {
                TreeSet<String>[] s = sides.computeIfAbsent(fId.getInt(m), k -> new TreeSet[]{ new TreeSet<>(), new TreeSet<>() });
                for (String x : l) if (!r.contains(x)) s[0].add(x);
                for (String x : r) if (!l.contains(x)) s[1].add(x);
            }
            balanceAt(left(m), t == null ? null : ((X) t).left, sides, branches);
            balanceAt(right(m), t == null ? null : ((X) t).right, sides, branches);
        } else if (g instanceof GInteraction i) {
            List<GType> ns = next(i);
            I ti = (I) t;
            if (ns.size() > 1 && ti != null && ti.choice != null) {
                List<Set<String>> rs = new ArrayList<>();
                for (GType n : ns) {
                    Set<String> s = names(n.getLiveRoles());
                    s.remove(i.src().toString());
                    s.remove(i.dst().toString());
                    rs.add(s);
                }
                Set<String> any = new TreeSet<>(), every = new TreeSet<>(rs.get(0));
                for (Set<String> s : rs) { any.addAll(s); every.retainAll(s); }
                any.removeAll(every);
                for (String x : any)
                    for (int k = 0; k < rs.size(); k++)
                        if (rs.get(k).contains(x))
                            branches.computeIfAbsent(ti.choice, c -> new TreeMap<>())
                                    .computeIfAbsent(x, y -> new TreeSet<>()).add(ti.cases.get(k).label());
            }
            for (int k = 0; k < ns.size(); k++) balanceAt(ns.get(k), ti == null ? null : ti.cases.get(k).next, sides, branches);
        } else if (g instanceof GRec r) {
            balanceAt(r.body(), t == null ? null : ((Rc) t).body, sides, branches);
        }
    }

    private static Set<String> names(scala.collection.immutable.Set<Role> s) {
        Set<String> r = new TreeSet<>();
        for (Role x : CollectionConverters.asJava(s)) r.add(x.toString());
        return r;
    }

    /* --- projection --- */

    private void projection(String proto, GInteractionSeq body, String role) throws Exception {
        GType g = Scrib2GT.translateSeq(body);
        int base = Integer.MAX_VALUE;
        for (Object o : CollectionConverters.asJava(g.getMids())) base = Math.min(base, (Integer) o);
        T mirror = new Mirror(base == Integer.MAX_VALUE ? 0 : base - 1).seq(body);
        if (!same(g, mirror)) return;
        Role r = null;
        for (Role x : CollectionConverters.asJava(g.getLiveRoles())) if (x.toString().equals(role)) r = x;
        if (r == null) return;
        projects(proto, g, mirror, g.getLiveRoles(), r);
    }

    // Projects onto r? If not, and every part does, it is the culprit.
    @SuppressWarnings("unchecked")
    private boolean projects(String proto, GType g, T t, scala.collection.immutable.Set<Role> all, Role r) throws Exception {
        boolean ok = true;
        if (g instanceof GInteraction i) {
            List<GType> ns = next(i);
            for (int k = 0; k < ns.size(); k++) ok &= projects(proto, ns.get(k), ((I) t).cases.get(k).next, all, r);
        } else if (g instanceof GMixed m) {
            ok &= projects(proto, left(m), ((X) t).left, all, r);
            ok &= projects(proto, right(m), ((X) t).right, all, r);
        } else if (g instanceof GRec rec) {
            ok &= projects(proto, rec.body(), ((Rc) t).body, all, r);
        }
        if (!ok) return false;
        if (g.rprojectAux(all, EPSILON, r).isDefined()) return true;

        String who = "`" + r + "`";
        if (g instanceof GMixed m) {
            X x = (X) t;
            scala.Tuple2<?, ?> l = (scala.Tuple2<?, ?>) left(m).rprojectAux(all, EPSILON, r).get();
            scala.Tuple2<?, ?> rt = (scala.Tuple2<?, ?>) right(m).rprojectAux(all, EPSILON, r).get();
            P p = mixedProblem("projection", proto, x.at);
            if (!l._2().equals(rt._2())) p.text.add("messages in flight differ between the sides");
            else if (rt._1() == LEnd$.MODULE$) { p.text.add(who + " takes no part on the right"); p.hard.add(first(x.at.getOtherChild())); }
            else p.text.add(who + "'s part on the right must start with a message");
        } else if (g instanceof GInteraction i && ((I) t).choice != null) {
            I ti = (I) t;
            P p = choiceProblem("projection", proto, ti.choice);
            if (r.equals(i.src()) || r.equals(i.dst())) { p.text.add("messages in flight differ between the branches"); return false; }
            List<Object> ls = new ArrayList<>();
            for (GType n : next(i)) ls.add(((scala.Tuple2<?, ?>) n.rprojectAux(all, EPSILON, r).get())._1());
            p.text.add(mergeFailure(who, ls));
            Set<String> one = Set.of(r.toString());
            for (GProtoBlock b : ti.choice.getBlockChildren()) involving(b, one, p.hard);
        }
        return false;
    }

    // Why the branches' local types do not merge (LType.merge): only inputs from one role, labels apart.
    private static String mergeFailure(String who, List<Object> ls) {
        boolean branches = true;
        Set<String> from = new TreeSet<>();
        for (Object l : ls) {
            if (l instanceof LSelect) return who + " sends in a branch before it can know which branch was taken";
            if (l instanceof LMixed) return who + "'s part in a branch starts with a mixed choice; it must start with a message";
            if (l instanceof LBranch b) from.add(b.src().toString()); else branches = false;
        }
        if (!branches) return who + " gets no message telling it which branch was taken";
        if (from.size() > 1) {
            List<String> f = new ArrayList<>(from);
            return who + " hears from `" + f.get(0) + "` in one branch and `" + f.get(1) + "` in another";
        }
        Set<String> seen = new HashSet<>();
        for (Object l : ls)
            for (Object k : CollectionConverters.asJava(((LBranch) l).cases()).keySet()) {
                String lab = label((scala.Tuple2<?, ?>) k);
                if (!seen.add(lab)) return who + " gets `" + lab + "` in more than one branch";
            }
        return "messages in flight differ between the branches";
    }

    private static String label(scala.Tuple2<?, ?> k) {
        StringBuilder b = new StringBuilder();
        for (Object d : CollectionConverters.asJava(((com.github.rhu1.gt.type.session.Payload) k._2()).elems()))
            b.append(b.length() > 0 ? ", " : "").append(d);
        return k._1() + "(" + b + ")";
    }

    /* --- what the translation rejects --- */

    private void structure(String proto, GProtoDecl decl, GInteractionSeq body) {
        if (body.getInteractionChildren().isEmpty()) {
            P p = problem("empty", proto, first(decl), "global protocol " + proto);
            p.text.add("the protocol is empty");
            p.hard.add(first(decl));
        } else {
            sequence(proto, body);
        }
    }

    private void sequence(String proto, GInteractionSeq s) {
        List<GSessionNode> cs = s.getInteractionChildren();
        for (int i = 0; i < cs.size() - 1; i++)
            if (!(cs.get(i) instanceof GMsgTransfer)) notLast(proto, (CommonTree) cs.get(i), (CommonTree) cs.get(i + 1));
        for (GSessionNode n : cs) {
            if (n instanceof GChoice c) {
                List<GProtoBlock> bs = c.getBlockChildren();
                for (int k = 0; k < bs.size(); k++)
                    block(proto, c, bs.get(k), "branch " + (k + 1), "each branch");
                choiceRoles(proto, c);
            } else if (n instanceof GRecursion r) {
                GInteractionSeq b = r.getBlockChild().getInteractSeqChild();
                if (b.getInteractionChildren().isEmpty()) {
                    P p = problem("empty", proto, first(r), "rec " + r.getRecVarChild());
                    p.text.add("its body is empty");
                    p.hard.add(first(r));
                } else {
                    sequence(proto, b);
                }
            } else if (n instanceof GTGMixed x) {
                block(proto, x, x.getLeftBlockChild(), "the left side", "each side");
                block(proto, x, x.getRightBlockChild(), "the right side", "each side");
                mixedRoles(proto, x);
            }
        }
    }

    private void notLast(String proto, CommonTree n, CommonTree follower) {
        String what = n instanceof GTGMixed ? "a mixed choice" : n instanceof GChoice ? "a choice"
                    : n instanceof GRecursion ? "a rec" : "a continue";
        P p = n instanceof GTGMixed x ? mixedProblem("translate", proto, x)
            : n instanceof GChoice c ? choiceProblem("translate", proto, c)
            : problem("translate", proto, first(n), n instanceof GRecursion r ? "rec " + r.getRecVarChild()
                                                    : "continue " + ((GContinue) n).getRecVarChild());
        p.text.add("line " + first(follower) + " follows it: " + what + " must be last in its block");
        p.soft.add(first(n));
        p.hard.add(first(follower));
    }

    // A side or branch: not empty, and translates to a message (Scrib2GT unfolds two recs).
    private void block(String proto, CommonTree owner, GProtoBlock b, String which, String rule) {
        GInteractionSeq s = b.getInteractSeqChild();
        if (s.getInteractionChildren().isEmpty()) {
            P p = owner instanceof GTGMixed x ? mixedProblem("empty", proto, x) : choiceProblem("empty", proto, (GChoice) owner);
            p.text.add(which + " is empty");
            p.hard.add(first(b));
            return;
        }
        GSessionNode bad = opening(s, 0);
        if (bad != null) {
            P p = owner instanceof GTGMixed x ? mixedProblem("first", proto, x) : choiceProblem("first", proto, (GChoice) owner);
            String what = bad instanceof GTGMixed ? "a mixed choice" : bad instanceof GContinue k ? "`continue " + k.getRecVarChild() + "`"
                        : "a rec in a rec in a rec";
            p.text.add(which + " starts with " + what + "; " + rule + " must start with a message");
            p.hard.add(first((CommonTree) bad));
        }
        sequence(proto, s);
    }

    private static GSessionNode opening(GInteractionSeq s, int unfolds) {
        List<GSessionNode> cs = s.getInteractionChildren();
        if (cs.size() != 1) return null;                     // empty, or a message first
        GSessionNode n = cs.get(0);
        if (n instanceof GMsgTransfer || n instanceof GChoice) return null;
        if (n instanceof GRecursion r) {
            GInteractionSeq b = r.getBlockChild().getInteractSeqChild();
            return unfolds < 2 ? (b.getInteractionChildren().isEmpty() ? null : opening(b, unfolds + 1)) : r;
        }
        return n;
    }

    // The first message a block translates to, if any.
    private static GMsgTransfer firstMessage(GInteractionSeq s, int unfolds) {
        List<GSessionNode> cs = s.getInteractionChildren();
        if (cs.isEmpty()) return null;
        GSessionNode n = cs.get(0);
        if (n instanceof GMsgTransfer m) return m;
        if (cs.size() > 1) return null;
        if (n instanceof GChoice c) return firstMessage(c.getBlockChildren().get(0).getInteractSeqChild(), unfolds);
        if (n instanceof GRecursion r && unfolds < 2) return firstMessage(r.getBlockChild().getInteractSeqChild(), unfolds + 1);
        return null;
    }

    private static String src(GMsgTransfer m) { return m.getSourceChild().toString(); }
    private static String dst(GMsgTransfer m) { return m.getDestinationChildren().get(0).toString(); }

    // Every branch opens with the subject sending, all to one role.
    private void choiceRoles(String proto, GChoice c) {
        String subject = c.getSubjectChild().toString();
        List<GMsgTransfer> fs = new ArrayList<>();
        for (GProtoBlock b : c.getBlockChildren()) {
            GMsgTransfer m = firstMessage(b.getInteractSeqChild(), 0);
            if (m != null) fs.add(m);
        }
        for (GMsgTransfer m : fs)
            if (!src(m).equals(subject)) {
                P p = choiceProblem("choice", proto, c);
                p.text.add("line " + first(m) + " starts with `" + src(m) + "` sending; each branch must start with `" + subject + "` sending");
                p.hard.add(first(m));
            }
        Set<String> to = new TreeSet<>();
        for (GMsgTransfer m : fs) if (src(m).equals(subject)) to.add(dst(m));
        if (to.size() > 1) {
            P p = choiceProblem("choice", proto, c);
            p.text.add("the branches start with messages to " + names(to) + "; each must go to the same role");
            for (GMsgTransfer m : fs) p.hard.add(first(m));
        }
    }

    // The right side opens with the reverse of the left's first message.
    private void mixedRoles(String proto, GTGMixed x) {
        GMsgTransfer l = firstMessage(x.getLeftBlockChild().getInteractSeqChild(), 0);
        GMsgTransfer r = firstMessage(x.getRightBlockChild().getInteractSeqChild(), 0);
        if (l == null || r == null || src(l).equals(dst(r)) && dst(l).equals(src(r))) return;
        P p = mixedProblem("mixed-roles", proto, x);
        p.text.add("the right side must start with a message from `" + dst(l) + "` to `" + src(l) + "`, the reverse of line " + first(l));
        p.hard.add(first(r));
    }

    /* --- the checker's types --- */

    private Field field(String name) throws NoSuchFieldException {
        Field f = GMixed.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private GInteraction left(GMixed m) throws IllegalAccessException { return (GInteraction) fLeft.get(m); }
    private GInteraction right(GMixed m) throws IllegalAccessException { return (GInteraction) fRight.get(m); }

    private static List<GType> next(GInteraction i) {
        List<GType> r = new ArrayList<>();
        for (Object v : CollectionConverters.asJava(i.cases()).values()) r.add((GType) v);
        return r;
    }

    private static List<String> keys(GInteraction i) {
        List<String> r = new ArrayList<>();
        for (Object k : CollectionConverters.asJava(i.cases()).keySet()) r.add(label((scala.Tuple2<?, ?>) k));
        return r;
    }

    // The checker's type and the mirror have the same shape and labels.
    private boolean same(GType g, T t) throws Exception {
        if (g instanceof GInteraction i && t instanceof I ti) {
            if (!i.src().toString().equals(ti.src) || !i.dst().toString().equals(ti.dst)) return false;
            List<GType> ns = next(i);
            List<String> ks = keys(i);
            if (ns.size() != ti.cases.size()) return false;
            for (int k = 0; k < ns.size(); k++)
                if (!ks.get(k).equals(ti.cases.get(k).label()) || !same(ns.get(k), ti.cases.get(k).next)) return false;
            return true;
        }
        if (g instanceof GMixed m && t instanceof X x)
            return fId.getInt(m) == x.id && same(left(m), x.left) && same(right(m), x.right);
        if (g instanceof GRec r && t instanceof Rc rc)
            return r.rvar().toString().equals(rc.var) && same(r.body(), rc.body);
        if (g instanceof GRecVar v && t instanceof V tv) return v.rvar().toString().equals(tv.var);
        return g == GEnd$.MODULE$ && t == END;
    }

    /* --- the mirror: Scrib2GT's translation, keeping each message's node --- */

    private abstract static class T { }
    private static final T END = new T() { };

    private record Case(String op, String pay, GMsgTransfer at, T next) {
        String label() { return op + "(" + pay + ")"; }
        Case then(T n) { return new Case(op, pay, at, n); }
    }

    private static final class I extends T {
        final String src, dst; final List<Case> cases; final GChoice choice;
        I(String src, String dst, List<Case> cases, GChoice choice) { this.src = src; this.dst = dst; this.cases = cases; this.choice = choice; }
    }

    private static final class X extends T {
        final int id; final I left, right; final String other, obs; final GTGMixed at;
        X(int id, I left, String other, String obs, I right, GTGMixed at) {
            this.id = id; this.left = left; this.other = other; this.obs = obs; this.right = right; this.at = at;
        }
    }

    private static final class Rc extends T {
        final String var; final T body;
        Rc(String var, T body) { this.var = var; this.body = body; }
    }

    private static final class V extends T {
        final String var;
        V(String var) { this.var = var; }
    }

    // Ids as nextMid gives them: after both sides, in source order.
    private static final class Mirror {
        private int next;
        Mirror(int base) { next = base; }

        T seq(GInteractionSeq s) {
            List<GSessionNode> cs = s.getInteractionChildren();
            T acc = node(cs.get(cs.size() - 1));
            for (int i = cs.size() - 2; i >= 0; i--) {
                if (!(cs.get(i) instanceof GMsgTransfer m)) throw new IllegalStateException("not last");
                acc = message(m, acc);
            }
            return acc;
        }

        T node(GSessionNode n) {
            if (n instanceof GMsgTransfer m) return message(m, END);
            if (n instanceof GChoice c) return choice(c);
            if (n instanceof GRecursion r) return new Rc(r.getRecVarChild().toString(), seq(r.getBlockChild().getInteractSeqChild()));
            if (n instanceof GContinue k) return new V(k.getRecVarChild().toString());
            if (n instanceof GTGMixed x) return mixed(x);
            throw new IllegalStateException("node");
        }

        I message(GMsgTransfer m, T then) {
            SigLitNode sig = (SigLitNode) m.getMessageNodeChild();
            StringBuilder pay = new StringBuilder();
            for (Object e : sig.getPayloadListChild().toPayload().elems) pay.append(pay.length() > 0 ? ", " : "").append(e);
            return new I(src(m), dst(m), List.of(new Case(sig.getOpChild().toString(), pay.toString(), m, then)), null);
        }

        // A repeated label: the last branch's, in the first's place (ListMap).
        I choice(GChoice c) {
            String src = c.getSubjectChild().toString();
            List<I> bs = new ArrayList<>();
            for (GProtoBlock b : c.getBlockChildren()) bs.add((I) unfoldTwice(seq(b.getInteractSeqChild())));
            String dst = bs.get(0).dst;
            for (I b : bs) if (!b.src.equals(src) || !b.dst.equals(dst)) throw new IllegalStateException("choice");
            LinkedHashMap<String, Case> cases = new LinkedHashMap<>();
            for (I b : bs) for (Case k : b.cases) cases.put(k.label(), k);
            return new I(src, dst, new ArrayList<>(cases.values()), c);
        }

        X mixed(GTGMixed x) {
            I l = (I) unfoldTwice(seq(x.getLeftBlockChild().getInteractSeqChild()));
            I r = (I) unfoldTwice(seq(x.getRightBlockChild().getInteractSeqChild()));
            if (!l.src.equals(r.dst) || !l.dst.equals(r.src)) throw new IllegalStateException("mixed");
            return new X(++next, l, x.getOtherChild().toString(), x.getObserverChild().toString(), r, x);
        }
    }

    private static void mixedChoices(T t, Map<Integer, X> into) {
        if (t instanceof X x) { into.putIfAbsent(x.id, x); mixedChoices(x.left, into); mixedChoices(x.right, into); }
        else if (t instanceof I i) { for (Case k : i.cases) mixedChoices(k.next, into); }
        else if (t instanceof Rc r) mixedChoices(r.body, into);
    }

    private static T unfold(T t) { return t instanceof Rc r ? subs(r.body, Map.of(r.var, r)) : t; }

    private static T unfoldTwice(T t) { return unfold(unfold(t)); }      // unfoldAllImmediate

    private static T subs(T t, Map<String, T> m) {
        if (t instanceof I i) {
            List<Case> cs = new ArrayList<>();
            for (Case k : i.cases) cs.add(k.then(subs(k.next, m)));
            return new I(i.src, i.dst, cs, i.choice);
        }
        if (t instanceof X x) return new X(x.id, (I) subs(x.left, m), x.other, x.obs, (I) subs(x.right, m), x.at);
        if (t instanceof Rc r) return m.containsKey(r.var) ? r : new Rc(r.var, subs(r.body, m));
        if (t instanceof V v) return m.getOrDefault(v.var, v);
        return t;
    }

    // unfoldAllOnce
    private static T once(T t, Set<String> done) {
        if (t instanceof I i) {
            List<Case> cs = new ArrayList<>();
            for (Case k : i.cases) cs.add(k.then(once(k.next, done)));
            return new I(i.src, i.dst, cs, i.choice);
        }
        if (t instanceof X x) return new X(x.id, (I) once(x.left, done), x.other, x.obs, (I) once(x.right, done), x.at);
        if (t instanceof Rc r) {
            if (done.contains(r.var)) return r;
            Set<String> d = new HashSet<>(done);
            d.add(r.var);
            return once(unfold(r), d);
        }
        return t;
    }

    /* --- committing labels, with lines: getCommittingAux and getNotCommittingAux --- */

    private final class Labels {
        final Map<String, Map<String, TreeSet<Integer>>> at = new TreeMap<>();
        void add(String role, Case k) {
            at.computeIfAbsent(role, r -> new TreeMap<>()).computeIfAbsent(k.op, o -> new TreeSet<>()).add(first(k.at));
        }
        TreeSet<Integer> lines(String role, String op) { return at.getOrDefault(role, Map.of()).getOrDefault(op, new TreeSet<>()); }
        Map<String, TreeSet<String>> ops() {
            Map<String, TreeSet<String>> r = new TreeMap<>();
            for (var e : at.entrySet()) r.put(e.getKey(), new TreeSet<>(e.getValue().keySet()));
            return r;
        }
    }

    private static Set<String> plus(Set<String> s, String x) { Set<String> r = new HashSet<>(s); r.add(x); return r; }

    private static Set<String> two(String a, String b) { Set<String> r = new HashSet<>(); r.add(a); r.add(b); return r; }

    private void committing(T t, boolean in, int c, Set<String> com, Labels acc) {
        if (t instanceof I i) {
            boolean step = !com.contains(i.dst) && com.contains(i.src);
            if (step && in) for (Case k : i.cases) acc.add(i.dst, k);
            Set<String> com1 = step ? plus(com, i.dst) : com;
            for (Case k : i.cases) committing(k.next, in, c, com1, acc);
        } else if (t instanceof X x) {
            if (x.id == c) {
                if (in) return;
                for (Case k : x.right.cases) acc.add(x.other, k);
                for (Case k : x.left.cases) acc.add(x.obs, k);
                for (Case k : x.left.cases) committing(k.next, true, c, Set.of(x.obs), acc);
                for (Case k : x.right.cases) committing(k.next, true, c, two(x.obs, x.other), acc);
            } else {
                for (Case k : x.left.cases) committing(k.next, in, c, com, acc);
                for (Case k : x.right.cases) committing(k.next, in, c, com, acc);
            }
        } else if (t instanceof Rc r) {
            committing(r.body, in, c, com, acc);
        }
    }

    private void notCommitting(T t, boolean in, int c, Set<String> com, Labels acc) {
        if (t instanceof I i) {
            boolean step = !com.contains(i.dst) && com.contains(i.src);
            if (!step && in) for (Case k : i.cases) acc.add(i.dst, k);
            Set<String> com1 = step ? plus(com, i.dst) : com;
            for (Case k : i.cases) notCommitting(k.next, in, c, com1, acc);
        } else if (t instanceof X x) {
            if (x.id == c) {
                if (in) return;
                for (Case k : x.left.cases) notCommitting(k.next, true, c, Set.of(x.obs), acc);
                for (Case k : x.right.cases) notCommitting(k.next, true, c, two(x.obs, x.other), acc);
            } else {
                for (Case k : x.left.cases) notCommitting(k.next, in, c, com, acc);
                for (Case k : x.right.cases) notCommitting(k.next, in, c, com, acc);
            }
        } else if (t instanceof Rc r) {
            notCommitting(r.body, in, c, com, acc);
        }
    }
}
