/* Shared by all pages: theme, the four checks, highlighting, editor line numbers, and the
   parts every page pulls in: header.html, footer.html, Markdown text (data-md) and HTML parts
   (data-include). MMST.ready settles once they are all in place. */
(function(){
  "use strict";

  try {
    var saved = localStorage.getItem("mmst.theme");
    if (saved) document.documentElement.setAttribute("data-theme", saved);
  } catch (e) {}

  var MMST = window.MMST = window.MMST || {};

  MMST.order  = ["WF", "SD", "CT", "BA"];
  MMST.checks = {
    WF: ["well-formed",       "Each label is committing everywhere or nowhere, so a receiver knows which side of the mixed choice it is on."],
    SD: ["single-decision",   "On the right, every role depends on the observer: an overrule reaches everyone."],
    CT: ["clear-termination", "On the left, the observer reaches every role, directly or transitively: no role finishes without learning the observer's decision."],
    BA: ["balanced",          "The branches of a choice involve the same roles: nobody waits for a message only one branch sends."]
  };

  // Regex highlighter for Scribble and Erlang.
  var KEYWORDS = {
    scribble: ["module","global","protocol","role","mixed","choice","at","from","to","or","rec","continue","data","as","import"],
    erlang:   ["case","of","end","fun","when","receive","after","if","andalso","orelse","not","and","or",
               "module","export","spec","record","define","behaviour","behavior","let","try","catch","throw","exit"]
  };
  function escHtml(s){ return String(s).replace(/[&<>]/g, function(c){ return {"&":"&amp;","<":"&lt;",">":"&gt;"}[c]; }); }
  MMST.highlight = function(text, lang){
    var kw = KEYWORDS[lang] || KEYWORDS.scribble;
    var commentSrc = lang === "erlang" ? "%[^\\n]*" : "//[^\\n]*";
    var tokenRe = new RegExp(
      "(" + commentSrc + ")" +                    // 1 comment
      "|(\"(?:\\\\.|[^\"\\\\])*\")" +              // 2 double-quoted string
      "|('(?:\\\\.|[^'\\\\])*')" +                 // 3 single-quoted atom (Erlang)
      "|(\\b\\d+(?:\\.\\d+)?\\b)" +                // 4 number
      "|(\\b(?:" + kw.join("|") + ")\\b)" +        // 5 keyword
      "|([A-Z][A-Za-z0-9_]*)",                     // 6 role / variable / Erlang Var
      "g"
    );
    return escHtml(text).replace(tokenRe, function(m, com, str, atom, num, k, id){
      if (com)  return '<span class="tok-com">' + com + '</span>';
      if (str)  return '<span class="tok-str">' + str + '</span>';
      if (atom) return '<span class="tok-str">' + atom + '</span>';
      if (num)  return '<span class="tok-num">' + num + '</span>';
      if (k)    return '<span class="tok-kw">' + k + '</span>';
      if (id)   return '<span class="tok-var">' + id + '</span>';
      return m;
    });
  };

  // Global and local types, as in the paper: A→B:a1 . … ⊳ …, B⊕a1 . C&a5 . end, Shop⊕{buy: end, …}.
  // Roles, labels, ⊕ and &, the ⊳ between the sides, end, // comments, ✓ and ✗.
  var MARKS = { "✓": "tok-ok", "✗": "tok-bad", "⊳": "tok-mc", "▷": "tok-mc", "⊕": "tok-op", "&": "tok-op", "→": "tok-arrow" };
  MMST.highlightTypes = function(text){
    var out = "", re = /(\/\/[^\n]*)|([✓✗⊳▷⊕&→])|([A-Za-z_][A-Za-z0-9_]*)|([^\/✓✗⊳▷⊕&→A-Za-z_]+|\/)/g, m;
    function span(cls, s){ return '<span class="' + cls + '">' + escHtml(s) + '</span>'; }
    while ((m = re.exec(text))) {
      if (m[1]) { out += span("tok-com", m[1]); continue; }
      if (m[2]) { out += span(MARKS[m[2]], m[2]); continue; }
      if (m[4] !== undefined) { out += escHtml(m[0]); continue; }
      var id = m[3], before = text.slice(0, m.index).replace(/[ \t]+$/, ""), after = text.slice(re.lastIndex);
      var prev = before.slice(-1), next = after.replace(/^[ \t]+/, "").charAt(0);
      if (id === "end") out += span("tok-kw", id);
      else if (next === "⊕" || next === "&" || next === "→" || prev === "→" ||
               (next === ":" && /(^|\n)[ \t]*$/.test(before))) out += span("tok-role", id);
      else if ("⊕&:{,".indexOf(prev) >= 0 && prev) out += span("tok-label", id);
      else out += escHtml(id);
    }
    return out;
  };

  // Line numbers for an editor (div.lnum): a textarea over its highlighted <code>, as in
  // index.html. The highlight is redrawn after every change to the text, so each redraw
  // recounts; the textarea's scroll moves the numbers. A line marked in the highlight
  // (mark.eline, the lines at fault; mark.esoft, the construct) is marked here too. A click
  // selects the line.
  MMST.lineNumbers = function(ta, code){
    var box = ta.parentNode, g = document.createElement("div"), col = document.createElement("div"), last = "";
    g.className = "lnum";
    g.setAttribute("aria-hidden", "true");
    g.appendChild(col);
    box.insertBefore(g, box.firstChild);
    function move(){ col.style.transform = "translateY(" + -ta.scrollTop + "px)"; }
    function draw(){
      var lines = code.innerHTML.split("\n"), n = ta.value.split("\n").length, h = "";
      for (var i = 1; i <= n; i++) {
        var m = /<mark class="(eline|esoft)"/.exec(lines[i - 1] || "");
        h += (m ? '<span class="' + m[1] + '">' + i + "</span>" : i) + "\n";
      }
      if (h !== last) { col.innerHTML = h; last = h; }
      box.style.setProperty("--ln", Math.max(2, String(n).length) + "ch");
      move();
    }
    g.addEventListener("mousedown", function(e){
      var cs = getComputedStyle(ta), lh = parseFloat(cs.lineHeight) || 20;
      var line = Math.floor((e.clientY - g.getBoundingClientRect().top + ta.scrollTop - parseFloat(cs.paddingTop)) / lh);
      if (line < 0) return;
      var text = ta.value, start = 0;
      for (var i = 0; i < line; i++) { start = text.indexOf("\n", start) + 1; if (!start) return; }
      var end = text.indexOf("\n", start);
      e.preventDefault();
      ta.focus({ preventScroll: true });
      try { ta.setSelectionRange(start, end < 0 ? text.length : end); } catch (x) {}
    });
    ta.addEventListener("scroll", move);
    new MutationObserver(draw).observe(code, { childList: true });
    draw();
  };

  function each(list, f){ Array.prototype.forEach.call(list, f); }

  MMST.text = function(url){
    return fetch(url).then(function(r){
      if (!r.ok) throw new Error(url + ": HTTP " + r.status);
      return r.text();
    });
  };

  // [data-include="parts/x.html"]: the element's content becomes that file.
  MMST.include = function(root){
    var els = Array.prototype.slice.call(root.querySelectorAll("[data-include]"));
    return Promise.all(els.map(function(el){
      var url = el.getAttribute("data-include");
      el.removeAttribute("data-include");
      return MMST.text(url).then(function(html){ el.innerHTML = html; return MMST.include(el); });
    }));
  };

  // Markdown into el, with marked (vendor/marked.umd.js). Additions:
  //   "{.lede} text"   a paragraph starting with classes gets them;
  //   "{.reading}"     a paragraph of classes alone gives them to the next element;
  //   ```scribble, ```erlang and ```types code is highlighted; ✓ and ✗ are coloured in any code;
  //   on reading pages (opts.doc), # is the title, ## a section heading, ``` a code block.
  var MARK = /^\{((?:\s*\.[\w-]+)+)\s*\}\s*/;
  MMST.marks = function(el){
    each(el.querySelectorAll("p"), function(p){
      var m = MARK.exec(p.innerHTML);
      if (!m) return;
      var cls = m[1].trim().split(/\s+/).map(function(c){ return c.slice(1); });
      var rest = p.innerHTML.slice(m[0].length);
      if (rest.trim()) { p.innerHTML = rest; cls.forEach(function(c){ p.classList.add(c); }); return; }
      var next = p.nextElementSibling;
      if (next) cls.forEach(function(c){ next.classList.add(c); });
      p.parentNode.removeChild(p);
    });
  };
  MMST.md = function(el, src, opts){
    opts = opts || {};
    el.innerHTML = window.marked.parse(src);
    MMST.marks(el);
    each(el.querySelectorAll("pre > code"), function(c){
      var lang = /language-(scribble|erlang|types)/.exec(c.className);
      if (!lang) c.innerHTML = c.innerHTML.replace(/[✓✗]/g, function(x){ return '<span class="' + MARKS[x] + '">' + x + '</span>'; });
      else if (lang[1] === "types") c.innerHTML = MMST.highlightTypes(c.textContent);
      else c.innerHTML = MMST.highlight(c.textContent, lang[1]);
    });
    each(el.querySelectorAll(":not(pre) > code"), function(c){       // `⊕` and the like, in the text
      var cls = MARKS[c.textContent.trim()];
      if (cls) c.classList.add(cls);
    });
    if (opts.doc) {
      each(el.querySelectorAll("h1"), function(h){ h.classList.add("title"); });
      each(el.querySelectorAll("h2"), function(h){ h.classList.add("sec"); });
      each(el.querySelectorAll("pre"), function(p){ p.classList.add("code"); });
    }
    return MMST.include(el);
  };

  function header(){
    var h = document.getElementById("site-header");
    if (!h) return Promise.resolve();
    function shown(){ h.classList.add("ready"); }                // see theme.css: no jump while loading
    return MMST.text("header.html").then(function(html){
      var t = document.createElement("template");
      t.innerHTML = html;
      var slot = t.content.querySelector("[data-slot]");
      if (slot) {                                         // the page's own controls go here
        while (h.firstChild) slot.parentNode.insertBefore(h.firstChild, slot);
        slot.parentNode.removeChild(slot);
      }
      h.textContent = "";
      h.appendChild(t.content);
      var page = location.pathname.split("/").pop() || "index.html";
      each(h.querySelectorAll(".sub a"), function(a){
        if (a.getAttribute("href") === page) a.setAttribute("aria-current", "page");
      });
      var home = h.querySelector("a.home");
      if (home && page === "index.html") {                // no link to the page you are on
        while (home.firstChild) home.parentNode.insertBefore(home.firstChild, home);
        home.parentNode.removeChild(home);
      }
      var b = h.querySelector("#theme");
      if (b) b.addEventListener("click", function(){
        var root = document.documentElement, cur = root.getAttribute("data-theme");
        var next = cur === "dark" ? "light" : cur === "light" ? "" : "dark";
        if (next) root.setAttribute("data-theme", next);
        else root.removeAttribute("data-theme");
        try { localStorage.setItem("mmst.theme", next); } catch (e) {}
      });
    }).then(shown, function(e){ shown(); throw e; });
  }

  function footer(){
    var f = document.getElementById("site-footer");
    if (!f) return Promise.resolve();
    return MMST.text("footer.html").then(function(html){ f.innerHTML = html; });
  }

  function markdown(){
    return Promise.all(Array.prototype.map.call(document.querySelectorAll("[data-md]"), function(el){
      var url = el.getAttribute("data-md");
      el.removeAttribute("data-md");
      return MMST.text(url).then(function(src){ return MMST.md(el, src, { doc: el.hasAttribute("data-doc") }); });
    }));
  }

  var settle;
  MMST.ready = new Promise(function(resolve){ settle = resolve; });
  document.addEventListener("DOMContentLoaded", function(){
    var parts = [header(), footer(), markdown(), MMST.include(document.body)].map(function(p){
      return p.catch(function(e){ if (window.console) console.error(e); });
    });
    Promise.all(parts).then(function(){ settle(); });
  });
})();
