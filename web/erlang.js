/* Erlang mode: open, edit and run a set of modules (filled-in apps, generated/,
   or generated from the editor). MMST.erlang.init(host) once. Edits are kept per
   set for the session. */
(function(){
  "use strict";

  var MMST = window.MMST = window.MMST || {};
  function $(id){ return document.getElementById(id); }
  function esc(s){ return String(s).replace(/[&<>"]/g, function(c){ return {"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]; }); }

  var EDITS = "mmst.erl.edits", GENERATED = "mmst.erl.generated", LAST = "mmst.erl.last";
  function load(k){ try { return JSON.parse(sessionStorage.getItem(k) || "null"); } catch (e) { return null; } }
  function save(k, v){ try { sessionStorage.setItem(k, JSON.stringify(v)); } catch (e) {} }

  // Fingerprint of a file as opened: an edit is restored only onto the same original.
  function fingerprint(s){
    var h = 5381;
    for (var i = 0; i < s.length; i++) h = (h * 33 ^ s.charCodeAt(i)) | 0;
    return (h >>> 0).toString(36) + "." + s.length;
  }

  var host, list = null, listed, set = null, running = false, entered = false;
  var ta, hl, out, chips, sel;

  /* files */

  // Order: callbacks, helpers, headers, app/sup, gen_.
  function kind(f){
    if (/^gen_.*\.erl$/.test(f.name)) return 4;
    if (/\.hrl$/.test(f.name)) return 2;
    if (/_(app|sup)\.erl$/.test(f.name)) return 3;
    if (/-behaviou?r\(\s*gen_/.test(f.orig)) return 0;
    return 1;
  }
  var KIND_TITLE = [
    "callbacks: yours to write",
    "helper",
    "records and macros",
    "app and supervisor: how the roles start",
    "generated gen_statem: enforces the protocol"
  ];

  function file(name){ return set && set.files.filter(function(f){ return f.name === name; })[0]; }
  function current(){ return file(set.active); }
  function edited(f){ return f.content !== f.orig; }

  function open(meta, files){
    if (set) { clearTimeout(saveTimer); saveEdits(); }     // the last keystrokes too
    var kept = (load(EDITS) || {})[meta.id] || {};
    set = {
      id: meta.id, label: meta.label, group: meta.group, path: meta.path,
      protocol: meta.protocol || null, roles: meta.roles || [], marks: {},
      files: files.map(function(f){
        var e = kept[f.name], orig = f.content;
        return { name: f.name, orig: orig, content: e && e.h === fingerprint(orig) ? e.c : orig, view: null };
      })
    };
    set.files.sort(function(a, b){ return kind(a) - kind(b) || (a.name < b.name ? -1 : 1); });
    if (!set.roles.length)
      set.roles = set.files.map(function(f){ return (/^gen_(.+)\.erl$/.exec(f.name) || [])[1]; }).filter(Boolean);
    set.active = set.files.length ? set.files[0].name : null;
    save(LAST, set.id);
    editorOptions();
    drawChips();
    showFile(set.active);
    intro();
    address();
  }

  function saveEdits(){
    if (!set) return;
    var all = load(EDITS) || {}, mine = {}, any = false;
    set.files.forEach(function(f){
      if (edited(f)) { mine[f.name] = { h: fingerprint(f.orig), c: f.content }; any = true; }
    });
    if (any) all[set.id] = mine; else delete all[set.id];
    save(EDITS, all);
  }
  var saveTimer = 0;
  function saveSoon(){ clearTimeout(saveTimer); saveTimer = setTimeout(saveEdits, 300); }

  function openListed(id){
    status("opening…");
    return fetch("/api/erlang/files?id=" + encodeURIComponent(id))
      .then(function(r){ if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
      .then(function(d){
        status("");
        if (!d.ok) return message("", "Not found", d.error || "No such set.");
        open(d, d.files);
      })
      .catch(function(e){ status(""); offline(e); });
  }

  // Modules generated from the editor's protocol.
  function openGenerated(module, files){
    var g = { id: "editor", label: module, group: "editor", path: "generated from " + module + ".scr",
              protocol: module, files: files.filter(function(f){ return /\.(erl|hrl)$/.test(f.name); })
                                        .map(function(f){ return { name: f.name, content: f.content }; }) };
    save(GENERATED, g);
    open(g, g.files);
  }

  function generateFromEditor(){
    var source = host.protocol(), module = host.moduleName() || "Scratch";
    status("generating…");
    message("wait", "Generating", module + ".scr");
    host.post("/api/generate", { source: source })
      .then(function(r){
        status("");
        if (r.ok && r.files && r.files.length) return openGenerated(module, r.files);
        sel.value = set ? set.id : "";
        message("", "Not generated", (r.error || "Invalid protocol.") + " Check it in Protocol mode.");
      })
      .catch(function(e){ status(""); offline(e); });
  }

  /* editor */

  function drawChips(){
    var h = "", last = -1;
    set.files.forEach(function(f){
      var k = kind(f);
      if (last !== -1 && k !== last) h += '<span class="gap" aria-hidden="true"></span>';
      last = k;
      h += '<button data-name="' + esc(f.name) + '" class="k' + k + (edited(f) ? " edited" : "") +
           (set.marks[f.name] ? " bad" : "") + '"' + (f.name === set.active ? ' aria-current="true"' : '') + ' title="' +
           esc(f.name + ": " + KIND_TITLE[k] + (edited(f) ? " (edited)" : "")) + '">' + esc(f.name) + '</button>';
    });
    var n = set.files.filter(edited).length;
    if (n) h += '<button class="undo" data-reset="all" title="Revert every file">reset ' +
                (n === 1 ? "the edit" : "all " + n + " edits") + '</button>';
    chips.innerHTML = h;
    var f = set.active && current();
    $("erl-reset").disabled = !(f && edited(f));
    $("erl-where").textContent = set.path;
    $("erl-where").title = set.path;
  }

  function showFile(name){
    var was = set.active && current();
    if (was && was.name !== name) was.view = { top: ta.scrollTop, left: ta.scrollLeft, a: ta.selectionStart, b: ta.selectionEnd };
    set.active = name;
    var f = current();
    ta.value = f ? f.content : "";
    ta.readOnly = !f;
    paint(true);
    var v = f && f.view;
    ta.scrollTop = v ? v.top : 0;
    ta.scrollLeft = v ? v.left : 0;
    if (v) try { ta.setSelectionRange(v.a, v.b); } catch (e) {}
    else try { ta.setSelectionRange(0, 0); } catch (e) {}
    sync();
    drawChips();
  }

  // Highlight at most once a frame; mark error lines.
  var painted = 0;
  function paint(now){
    if (now) { painted = 0; return draw(); }
    if (!painted) painted = requestAnimationFrame(function(){ painted = 0; draw(); });
  }
  function draw(){
    var f = set && current();
    if (!f) { hl.innerHTML = ""; return; }
    var html = window.MMST.highlight(f.content, "erlang"), marks = set.marks[f.name];
    if (marks) {
      var lines = html.split("\n");
      Object.keys(marks).forEach(function(n){
        var i = +n - 1;
        if (i >= 0 && i < lines.length) lines[i] = '<mark class="eline">' + (lines[i] || " ") + '</mark>';
      });
      html = lines.join("\n");
    }
    hl.innerHTML = html + "\n";
    sync();
  }
  function sync(){ var pre = hl.parentElement; pre.scrollTop = ta.scrollTop; pre.scrollLeft = ta.scrollLeft; }

  function onInput(){
    var f = current();
    if (!f) return;
    var was = edited(f);
    f.content = ta.value;
    paint();
    saveSoon();
    if (was !== edited(f)) drawChips();
  }

  function reset(all){
    (all ? set.files : [current()]).forEach(function(f){ if (f) { f.content = f.orig; } });
    saveEdits();
    var f = current();
    ta.value = f ? f.content : "";
    paint(true);
    drawChips();
  }

  // Open a file at a line, a third of the way down.
  function jump(name, line){
    if (!file(name)) return;
    if (set.active !== name) showFile(name);
    var text = ta.value, start = 0;
    for (var i = 1; i < line && start >= 0; i++) start = text.indexOf("\n", start) + 1 || -1;
    if (start < 0) return;
    var end = text.indexOf("\n", start);
    if (end < 0) end = text.length;
    ta.focus({ preventScroll: true });
    try { ta.setSelectionRange(start, end); } catch (e) {}
    var lh = parseFloat(getComputedStyle(ta).lineHeight) || 20;
    ta.scrollTop = Math.max(0, (line - 1) * lh - ta.clientHeight / 3);
    ta.scrollLeft = 0;
    sync();
    if (narrow()) ta.scrollIntoView({ block: "nearest" });
  }
  function narrow(){ return window.matchMedia("(max-width:900px)").matches; }

  /* .zip of the set with edits; stored, not compressed. */
  var CRC = (function(){
    var t = [];
    for (var n = 0; n < 256; n++) {
      var c = n;
      for (var k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
      t[n] = c >>> 0;
    }
    return t;
  })();
  function crc32(b){
    var c = 0xFFFFFFFF;
    for (var i = 0; i < b.length; i++) c = CRC[(c ^ b[i]) & 0xFF] ^ (c >>> 8);
    return (c ^ 0xFFFFFFFF) >>> 0;
  }
  function zip(entries){
    var enc = new TextEncoder(), parts = [], dir = [], offset = 0, d = new Date();
    var time = d.getHours() << 11 | d.getMinutes() << 5 | d.getSeconds() >> 1;
    var date = (d.getFullYear() - 1980) << 9 | (d.getMonth() + 1) << 5 | d.getDate();
    function header(size){ var b = new DataView(new ArrayBuffer(size)); return b; }
    entries.forEach(function(e){
      var name = enc.encode(e.name), data = enc.encode(e.content), crc = crc32(data);
      var h = header(30);
      h.setUint32(0, 0x04034b50, true); h.setUint16(4, 20, true); h.setUint16(6, 0x0800, true);
      h.setUint16(10, time, true); h.setUint16(12, date, true); h.setUint32(14, crc, true);
      h.setUint32(18, data.length, true); h.setUint32(22, data.length, true); h.setUint16(26, name.length, true);
      var c = header(46);
      c.setUint32(0, 0x02014b50, true); c.setUint16(4, 20, true); c.setUint16(6, 20, true); c.setUint16(8, 0x0800, true);
      c.setUint16(12, time, true); c.setUint16(14, date, true); c.setUint32(16, crc, true);
      c.setUint32(20, data.length, true); c.setUint32(24, data.length, true); c.setUint16(28, name.length, true);
      c.setUint32(42, offset, true);
      parts.push(h, name, data);
      dir.push(c, name);
      offset += 30 + name.length + data.length;
    });
    var size = dir.reduce(function(n, x){ return n + x.byteLength; }, 0), end = header(22);
    end.setUint32(0, 0x06054b50, true); end.setUint16(8, entries.length, true); end.setUint16(10, entries.length, true);
    end.setUint32(12, size, true); end.setUint32(16, offset, true);
    return new Blob(parts.concat(dir, [end]), { type: "application/zip" });
  }
  function download(){
    if (!set) return;
    var base = (set.group === "generated" ? "generated-" : "") + set.label.replace(/[^A-Za-z0-9_.-]+/g, "_");
    var a = document.createElement("a");
    a.href = URL.createObjectURL(zip(set.files.map(function(f){ return { name: base + "/" + f.name, content: f.content }; })));
    a.download = base + ".zip";
    document.body.appendChild(a);
    a.click();
    setTimeout(function(){ URL.revokeObjectURL(a.href); a.remove(); }, 1000);
  }

  /* output */

  function show(html){ out.innerHTML = html; out.scrollTop = 0; }
  function message(kind, title, body, raw){
    show('<div class="result"><div class="msg' + (kind ? " " + kind : "") + '"><div class="t">' + esc(title) +
         '</div>' + (raw ? body : esc(body)) + '</div></div>');
  }
  function status(s){ $("erl-status").textContent = s; }
  function secs(ms){ return ms >= 10000 ? Math.round(ms / 1000) + " s" : (ms / 1000).toFixed(1) + " s"; }
  function title(s){ $("erl-title").textContent = s; }

  function offline(e){
    title("offline");
    message("", "Offline", "The server is unreachable. Your edits are kept in this tab. (" + e.message + ")");
  }

  function protocolLink(){
    var p = set.protocol;
    if (!p) return "";
    return host.hasProtocol(p)
      ? '<a href="#" data-protocol="' + esc(p) + '" title="Open in Protocol mode">' + esc(p) + '.scr</a>'
      : '<code>' + esc(p) + '.scr</code>';
  }

  // Before a run: what the set is.
  function intro(){
    set.marks = {};
    title("run");
    var p = protocolLink(), h = '<div class="result"><div class="msg"><div class="t">';
    if (set.group === "examples")
      h += 'filled in</div>' + (p ? p + ': ' : '') + 'the artifact\'s OTP app, <code>' + esc(set.path) +
           '</code>. Hand-written callbacks, generated <code>gen_</code> modules.';
    else if (set.group === "generated")
      h += 'generated</div>' + (p ? p + ': ' : '') + '<code>' + esc(set.path) +
           '</code>. Default callbacks print each receive and choose at random.';
    else
      h += 'from the editor</div>Generated from ' + (p || "the editor's protocol") +
           '. Default callbacks print each receive and choose at random.';
    h += '</div><p class="hintline"><b>Roles:</b> ' + set.roles.map(function(r, i){
      return '<span class="rc rc' + (i % 4) + '">' + esc(r) + '</span>'; }).join(", ") + '.</p>';
    if (list && !list.run) h += offNote();
    else h += '<p class="hintline"><b>Run</b>: compile, start the roles, run for up to ' + $("erl-secs").value + ' s.' +
              (list && list.jail ? ' ' + esc(list.jail) : '') + '</p>';
    show(h + '</div>');
  }

  function offNote(){
    var t = { off: "Runs off", "no-erlang": "No Erlang", launcher: "No launcher", local: "Localhost only",
              unconfined: "Unconfined", vm: "VM does not start" }[list.code] || "Runs off";
    var here = /^(localhost|127\.0\.0\.1|\[::1\])$/.test(location.hostname);
    var body = {
      off: here ? 'Runs are off: start the server with <code>MMST_ERLANG=1 ./serve.sh</code>. Editing still works.'
                : 'Runs are off on this server. Editing still works.',
      "no-erlang": 'No <code>erl</code> or <code>erlc</code> on the server\'s PATH. Editing still works.',
      local: 'Runs are only for the server\'s own machine. Editing still works.'
    }[list.code] || esc(list.why || "");
    return '<div class="msg wait"><div class="t">' + esc(t) + '</div>' + body + '</div>';
  }

  /* Log: lines tagged by role; *DBG* trace folded; logger reports collapsed. */
  var names;                                  // lower-case name -> role
  function roleIndex(r){ var i = set.roles.indexOf(r); return i < 0 ? -1 : i % 4; }
  function whose(line){
    var m;
    if ((m = /^\*DBG\* (\S+) /.exec(line))) return { role: names[m[1].toLowerCase()], dbg: true };
    if ((m = /^gen_([a-z]\w*)\[/.exec(line))) return { role: names[m[1].toLowerCase()] };
    if ((m = /^([A-Za-z]\w*)(?::\s| initialized\b| connected\b)/.exec(line))) return { role: lookup(m[1]) };
    return {};
  }
  // Callbacks name roles loosely ("Server:" for srv): a word matches a role of 3+
  // letters with the same first letter and its letters in order, if only one fits.
  function lookup(word){
    var w = word.toLowerCase();
    if (w in names) return names[w];
    var hits = set.roles.filter(function(r){
      if (r.length < 3 || r[0] !== w[0]) return false;
      for (var i = 0, j = 0; i < w.length && j < r.length; i++) if (w[i] === r[j]) j++;
      return j === r.length;
    });
    return names[w] = hits.length === 1 ? hits[0] : undefined;
  }
  function whoWidth(){
    var w = 3;
    set.roles.forEach(function(r){ w = Math.max(w, r.length); });
    return Math.min(w, 12);
  }
  function tag(role){
    var i = role ? roleIndex(role) : -1;
    return '<span class="who' + (i >= 0 ? " rc rc" + i : "") + '">' + esc(role || "") + '</span>';
  }
  function logHTML(text){
    var lines = text.split("\n"), h = [], dbg = 0;
    for (var i = 0; i < lines.length; i++) {
      var l = lines[i], m = /^=([A-Z ]+?) REPORT==== .* ===$/.exec(l);
      if (m) {
        var block = [l];
        while (i + 1 < lines.length && lines[i + 1] !== "") block.push(lines[++i]);
        var ex = /exception \w+: .+/.exec(block.join("\n"));
        var about = ex ? ex[0] : (block[1] || "").replace(/^\*\*\s*/, "").replace(/^\s+/, ""), who = null, w;
        if ((w = /(?:State machine|registered_name:)\s+(\w+)/.exec(block.join("\n")))) who = names[w[1].toLowerCase()];
        h.push('<details class="rep"><summary>' + tag(who) + '<span class="tx">' + esc(m[1].toLowerCase() + " report" +
               (about ? ": " + about : "")) + '</span></summary><pre>' + esc(block.join("\n")) + '</pre></details>');
        continue;
      }
      if (l === "" && i === lines.length - 1) break;
      var a = whose(l);
      if (a.dbg) dbg++;
      h.push('<div class="ln' + (a.dbg ? " dbg" : "") + '">' + tag(a.role) + '<span class="tx">' + esc(l) + '</span></div>');
    }
    return { html: h.join(""), dbg: dbg };
  }

  function where(p){
    return '<button class="loc" data-file="' + esc(p.file) + '" data-line="' + p.line + '">' +
           esc(p.file + (p.line ? ":" + p.line + (p.col ? ":" + p.col : "") : "")) + '</button>';
  }
  function problems(ps, cls){
    return '<ul class="erl-problems ' + cls + '">' + ps.map(function(p){
      return '<li>' + (file(p.file) ? where(p) : '<code>' + esc(p.file) + '</code>') + ' <span>' + esc(p.text) + '</span></li>';
    }).join("") + '</ul>';
  }
  // "(f.erl, line N)" becomes a link.
  function linkLines(s){
    return esc(s).replace(/\(([a-z]\w*\.erl), line (\d+)\)/g, function(all, f, n){
      return file(f) ? '(<button class="loc" data-file="' + f + '" data-line="' + n + '">' + f + ', line ' + n + '</button>)' : all;
    });
  }

  function roleRow(x){
    var alias = x.module && /^gen_/.test(x.module) ? x.module.slice(4) : null;
    var role = alias && set.roles.indexOf(alias) >= 0 ? alias : x.name;
    var h = '<li class="' + esc(x.status) + '"><b class="rc' + (roleIndex(role) >= 0 ? " rc" + roleIndex(role) : "") + '">' +
            esc(role) + (role !== x.name ? ' <span class="alias" title="registered as ' + esc(x.name) + '">' + esc(x.name) + '</span>' : '') + '</b>';
    if (x.status === "finished") h += '<span class="st">finished</span>';
    else if (x.status === "waiting")
      h += '<span class="st">waiting in <code>' + esc(x.state) + '</code></span><span class="more">' +
           [x.postponed ? x.postponed + " postponed" : "", x.queue ? x.queue + " in mailbox" : ""].filter(Boolean).join(" · ") + '</span>';
    else h += '<span class="st">' + (x.status === "failed" ? "did not start" : "crashed") + '</span><span class="more">' + linkLines(x.detail || "") + '</span>';
    return h + '</li>';
  }

  function render(r){
    set.marks = {};
    (r.errors || []).forEach(function(e){ (set.marks[e.file] = set.marks[e.file] || {})[e.line] = e.text; });
    paint(true);
    drawChips();
    names = {};
    set.roles.forEach(function(x){ names[x.toLowerCase()] = x; });
    (r.roles || []).forEach(function(x){
      var alias = x.module && /^gen_/.test(x.module) ? x.module.slice(4) : null;
      names[x.name.toLowerCase()] = alias && set.roles.indexOf(alias) >= 0 ? alias : x.name;
    });

    if (r.stage === "off") {
      list = list || { sets: [] };
      list.run = false; list.code = r.code; list.why = r.error;
      $("erl-run").disabled = true;
      title("off");
      return show('<div class="result">' + offNote() + '</div>');
    }
    if (r.stage === "busy") { title("busy"); return message("wait", "Busy", r.error); }
    if (r.stage === "clash") {
      title("not run");
      return message("", "Name clash",
        esc(r.error) + ' Clashing: ' + r.clash.map(function(m){ return '<code>' + esc(m) + '</code>'; }).join(", ") + '.', true);
    }
    if (r.stage === "error" && r.output === undefined) { title("not run"); return message("", "Not run", r.error || "Refused."); }

    var h = '<div class="result">', log = logHTML(r.output || "");
    if (r.stage === "compile") {
      title("did not compile");
      h += '<div class="verdict"><span class="tag no">did not compile</span><span class="name">' +
           r.errors.length + (r.errors.length === 1 ? " error" : " errors") + '</span></div>' + problems(r.errors, "errors");
      if (r.errors.some(function(e){ return /^gen_/.test(e.file); }) && set.group !== "examples")
        h += '<p class="hintline">Errors in generated modules: the generator\'s, not yours.</p>';
    } else {
      var roles = r.roles || [], n = { finished: 0, waiting: 0, crashed: 0, failed: 0 };
      roles.forEach(function(x){ n[x.status] = (n[x.status] || 0) + 1; });
      var bad = n.crashed + n.failed;
      title(r.stage === "error" ? "stopped" : "ran");
      h += '<div class="verdict">' +
           (bad ? '<span class="tag no">' + bad + ' crashed</span>'
                : n.waiting ? '<span class="tag wait">' + n.waiting + ' waiting</span>'
                : roles.length ? '<span class="tag ok">all finished</span>' : '<span class="tag wait">nothing ran</span>') +
           '<span class="name">compiled ' + r.compiled + ' modules in ' + secs(r.compileMs) +
           (r.stage === "run" && !r.nothing ? ' · ran ' + secs(r.runMs) : '') + '</span></div>';
      if (r.error) h += '<div class="msg"><div class="t">stopped</div>' + esc(r.error) + '</div>';
      if (r.nothing)
        h += '<p class="hintline">Nothing to start: no supervisor, and no module implements a <code>gen_</code> behaviour.</p>';
      if (roles.length) h += '<ul class="erl-roles">' + roles.map(roleRow).join("") + '</ul>';
      if (n.waiting)
        h += '<p class="hintline"><b>Waiting</b>: in that state at the deadline, with nothing it can take. ' +
             '<b>Postponed</b>: arrived, deferred until the state changes.</p>';
    }
    var pins = pinned();
    if (pins.n && /had no clause for/.test(r.output || ""))
      h += '<div class="msg"><div class="t">template stalls</div>Callbacks match the sender against a pid in ' +
           '<code>#state_data{}</code> that is still <code>undefined</code>. The filled-in apps drop that pattern.' +
           '<div class="msg-do"><button class="btn" id="erl-unpin">Drop the patterns</button> <span>' + pins.n +
           (pins.n === 1 ? ' pattern' : ' patterns') + ' in ' + pins.files.join(", ") + '</span></div></div>';
    (r.renamed || []).forEach(function(x){
      h += '<p class="hintline"><code>' + esc(x.from) + '</code> is an OTP module: run as <code>' + esc(x.to) + '</code>.</p>';
    });
    if (r.output) {
      h += '<div class="erl-logbar"><h3 class="sec">output</h3>' +
           (log.dbg ? '<label title="gen_statem debug trace">' +
                      '<input type="checkbox" id="erl-trace"' + (traceOn ? " checked" : "") + '> trace <span class="n">' + log.dbg + ' lines</span></label>' : '') +
           '</div><div class="erl-log' + (traceOn ? " trace" : "") + '" id="erl-log" style="--who:' + whoWidth() + 'ch">' + log.html + '</div>';
      if (r.truncated) h += '<p class="hintline">Output too long: the middle is cut.</p>';
    } else if (r.stage !== "compile") {
      h += '<p class="hintline">No output.</p>';
    }
    if (r.warnings && r.warnings.length)
      h += '<details class="raw"><summary>' + r.warnings.length + (r.moreWarnings ? "+" : "") + ' compiler warning' +
           (r.warnings.length === 1 ? "" : "s") + '</summary>' + problems(r.warnings, "warnings") + '</details>';
    show(h + '</div>');
    if (r.stage === "compile" && r.errors.length && file(r.errors[0].file) && !narrow()) jump(r.errors[0].file, r.errors[0].line);
  }
  var traceOn = false;

  /* Template callbacks match #state_data{peer_pid = P} = Data0, which fails while
     the pid is undefined. Dropping it, as the filled-in apps do, lets them run. */
  var PIN = /#state_data\{\w+_pid = \w+\} = (Data0?)\b/g;
  function pinned(){
    var n = 0, files = [];
    set.files.forEach(function(f){
      if (kind(f) !== 0) return;
      var k = (f.content.match(PIN) || []).length;
      if (k) { n += k; files.push(f.name); }
    });
    return { n: n, files: files };
  }
  function unpin(){
    var pins = pinned();
    set.files.forEach(function(f){ if (kind(f) === 0) f.content = f.content.replace(PIN, "$1"); });
    saveEdits();
    var f = current();
    ta.value = f ? f.content : "";
    paint(true);
    drawChips();
    title("run");
    message("", "Patterns dropped",
      pins.n + (pins.n === 1 ? " pattern" : " patterns") + " in " + pins.files.join(", ") + ". Reset reverts. Run again.");
  }

  function run(){
    if (!set || running) return;
    if (list && !list.run) { title("off"); return show('<div class="result">' + offNote() + '</div>'); }
    running = true;
    busy(true);
    title("running");
    var s = +$("erl-secs").value;
    message("wait", "Running", set.files.length + " files, up to " + s + " s.");
    host.post("/api/erlang/run", { seconds: s, files: set.files.map(function(f){ return { name: f.name, content: f.content }; }) })
      .then(render).catch(offline)
      .then(function(){ running = false; busy(false); }, function(){ running = false; busy(false); });
  }

  function busy(on){
    $("erl-run").disabled = on || !!(list && !list.run);
    sel.disabled = on;
    status(on ? "running…" : "");
  }

  /* menu */

  var GROUPS = [["examples", "Filled in"], ["generated", "Generated"]];
  function fillMenu(){
    var h = '<option value="">Open Erlang…</option><optgroup label="From the editor" id="erl-editor-group"></optgroup>';
    GROUPS.forEach(function(g){
      var items = list.sets.filter(function(s){ return s.group === g[0]; });
      if (!items.length) return;
      h += '<optgroup label="' + esc(g[1]) + '">' + items.map(function(s){
        return '<option value="' + esc(s.id) + '">' + esc(s.label) + '</option>';
      }).join("") + '</optgroup>';
    });
    sel.innerHTML = h;
    editorOptions();
  }
  // The last set generated from the editor, and the action to generate now.
  function editorOptions(){
    var g = $("erl-editor-group");
    if (!g) return;
    var gen = load(GENERATED), m = host.moduleName() || "Scratch";
    g.innerHTML = (gen ? '<option value="editor">' + esc(gen.label) + '.scr, generated</option>' : '') +
      '<option value="@editor" id="erl-from-editor">Generate ' + (gen && gen.label === m ? "again " : "") + 'from ' + esc(m) + '.scr</option>';
    sel.value = set ? set.id : "";
  }

  function address(){
    if (!entered) return;
    history.replaceState(null, "", "#mode=erlang" + (set ? "&erl=" + encodeURIComponent(set.id) : ""));
  }

  /* On entry: the requested set, else the app for the editor's protocol, else the last, else the first. */
  function choose(want){
    if (want === "editor") {
      var g = load(GENERATED);
      if (g) return open(g, g.files);
      return nothingOpen();
    }
    var ids = list ? list.sets.map(function(s){ return s.id; }) : [];
    if (want && ids.indexOf(want) >= 0) return openListed(want);
    var p = host.moduleName(), forP = list && list.sets.filter(function(s){ return s.group === "examples" && s.protocol === p; })[0];
    if (forP) return openListed(forP.id);
    var last = load(LAST);
    if (last === "editor" && load(GENERATED)) return choose("editor");
    if (last && ids.indexOf(last) >= 0) return openListed(last);
    if (ids.length) return openListed(ids[0]);
    return nothingOpen();
  }
  // Generating checks the protocol, so it waits to be asked.
  function nothingOpen(){
    sel.value = "";
    message("wait", "Nothing open", "Open a set from the menu, or choose <b>Generate from " +
            esc(host.moduleName() || "Scratch") + ".scr</b>.", true);
  }

  MMST.erlang = {
    init: function(h){
      host = h;
      ta = $("erl-src"); hl = $("erl-hl"); out = $("erl-out"); chips = $("erl-files"); sel = $("erlsets");
      listed = fetch("/api/erlang/list")
        .then(function(r){ if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
        .then(function(d){ list = d; fillMenu(); busy(false); if (set && !running && !out.querySelector(".verdict")) intro(); })
        .catch(function(){ list = { run: false, code: "offline", why: "No set list from the server.", sets: [] }; });

      ta.addEventListener("input", onInput);
      ta.addEventListener("scroll", sync);
      ta.addEventListener("keydown", function(e){
        if (e.key === "Tab" && !e.metaKey && !e.ctrlKey && !e.altKey) {
          e.preventDefault();
          ta.setRangeText("    ", ta.selectionStart, ta.selectionEnd, "end");
          onInput();
        }
      });
      chips.addEventListener("click", function(e){
        var b = e.target.closest("button");
        if (!b) return;
        if (b.dataset.reset) return reset(true);
        showFile(b.dataset.name);
      });
      $("erl-reset").addEventListener("click", function(){ reset(false); });
      $("erl-zip").addEventListener("click", download);
      $("erl-run").addEventListener("click", run);
      $("erl-secs").addEventListener("change", function(){ if (set && !out.querySelector(".verdict")) intro(); });
      sel.addEventListener("change", function(){
        if (sel.value === "@editor") generateFromEditor();
        else if (sel.value === "editor") choose("editor");
        else if (sel.value) openListed(sel.value);
      });
      out.addEventListener("click", function(e){
        if (e.target.closest("#erl-unpin")) return unpin();
        var b = e.target.closest("button.loc");
        if (b) return jump(b.dataset.file, +b.dataset.line);
        var a = e.target.closest("a[data-protocol]");
        if (a) { e.preventDefault(); host.openProtocol(a.dataset.protocol); }
      });
      out.addEventListener("change", function(e){
        if (e.target.id !== "erl-trace") return;
        traceOn = e.target.checked;
        $("erl-log").classList.toggle("trace", traceOn);
      });
      window.addEventListener("pagehide", saveEdits);
    },

    // Switched to Erlang mode; want: a set id from the address.
    enter: function(want){
      entered = true;
      editorOptions();
      if (set && !want) { address(); return; }
      listed.then(function(){ if (!set || want) choose(want); });
    },
    leave: function(){ entered = false; saveEdits(); },
    // Protocol menu loaded: the intro can link to the protocol.
    refresh: function(){ if (set && !running && !out.querySelector(".verdict")) intro(); },
    run: run,
    openGenerated: function(module, files){ entered = true; openGenerated(module, files); }
  };
})();
