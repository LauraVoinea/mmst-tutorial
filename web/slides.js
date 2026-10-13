/* Slides from Markdown: <main id="deck" data-slides="text/x.md">.
   A line "---" starts a new slide. "{.part}" or "{.title}" on a slide's first line gives the
   slide that class. "Note:" starts the speaker notes, to the end of the slide.
   Keys: → / space next, ← back, Home / End, g + number + Enter go to, n notes, o overview,
   f full screen. The slide number is in the address (#7). Links open in a new tab. */
(function(){
  "use strict";
  var MMST = window.MMST = window.MMST || {};
  var W = 1280, H = 720;                                       // the slide, before scaling

  function each(list, f){ Array.prototype.forEach.call(list, f); }

  // Top-level "---" splits slides (not inside code); [label]: url definitions apply to all.
  function split(src){
    var tokens = window.marked.lexer(src), out = [[]], defs = [];
    tokens.forEach(function(t){
      if (t.type === "hr") out.push([]);
      else if (t.type === "def") defs.push(t.raw.trim());
      else out[out.length - 1].push(t.raw);
    });
    var tail = defs.length ? "\n\n" + defs.join("\n") + "\n" : "";
    return out.map(function(raws){ return raws.join("").replace(/^\s*(<!--[\s\S]*?-->\s*)+/, ""); })
              .filter(function(md){ return md.trim(); })
              .map(function(md){
                var cls = [], m = /^\s*\{((?:\s*\.[\w-]+)+)\s*\}\s*\n/.exec(md);
                if (m) { cls = m[1].trim().split(/\s+/).map(function(c){ return c.slice(1); }); md = md.slice(m[0].length); }
                var n = /^Note:[ \t]*/m.exec(md), notes = "";
                if (n) { notes = md.slice(n.index + n[0].length); md = md.slice(0, n.index); }
                return { md: md + tail, notes: notes.trim() ? notes + tail : "", cls: cls };
              });
  }

  function build(deck, src){
    var view = deck.querySelector(".view"), stage = deck.querySelector(".stage"), counter = deck.querySelector(".counter"),
        notes = deck.querySelector(".notes"), overview = deck.querySelector(".overview"),
        progress = deck.querySelector(".progress span"), gotoBox = deck.querySelector(".goto");
    var parts = split(src), slides = [], loads = [], i = 0, gotoBuf = null;

    parts.forEach(function(p, k){
      var s = document.createElement("section");
      s.className = "slide";
      p.cls.forEach(function(c){ s.classList.add(c); });
      loads.push(MMST.md(s, p.md));
      s.notes = p.notes ? window.marked.parse(p.notes) : "";
      stage.appendChild(s);
      slides.push(s);
      var h = s.querySelector("h1, h2");
      var b = document.createElement("button");
      b.type = "button";
      b.innerHTML = '<span class="n">' + (k + 1) + '</span>' + (h ? h.textContent : "");
      b.addEventListener("click", function(){ overview.hidden = true; show(k); });
      overview.appendChild(b);
    });
    each(stage.querySelectorAll("a[href]"), function(a){ a.target = "_blank"; a.rel = "noopener"; });

    function fit(){                                           // the largest 16:9 that fits, with a margin
      var r = view.getBoundingClientRect(), s = 0.96 * Math.min(r.width / W, r.height / H);
      stage.style.transform = "translate(-50%, -50%) scale(" + Math.max(0.1, s) + ")";
    }
    function show(n){
      i = Math.max(0, Math.min(slides.length - 1, n));
      slides.forEach(function(s, k){ s.classList.toggle("on", k === i); });
      counter.textContent = (i + 1) + " / " + slides.length;
      progress.style.width = (slides.length > 1 ? 100 * i / (slides.length - 1) : 100) + "%";
      notes.innerHTML = slides[i].notes || "<p>No notes.</p>";
      try { history.replaceState(null, "", "#" + (i + 1)); } catch (e) {}
      document.dispatchEvent(new CustomEvent("mmst:slide", { detail: slides[i] }));
    }

    document.addEventListener("keydown", function(e){
      if (e.metaKey || e.ctrlKey || e.altKey) return;
      var k = e.key;
      if (gotoBuf !== null) {
        if (/^[0-9]$/.test(k)) { gotoBuf += k; gotoBox.textContent = "go to " + gotoBuf; e.preventDefault(); return; }
        if (k === "Enter" && gotoBuf) show(parseInt(gotoBuf, 10) - 1);
        gotoBuf = null; gotoBox.hidden = true; e.preventDefault(); return;
      }
      switch (k) {
        case "ArrowRight": case " ": case "PageDown": case "j": show(i + 1); break;
        case "ArrowLeft": case "PageUp": case "k": show(i - 1); break;
        case "Home": show(0); break;
        case "End": show(slides.length - 1); break;
        case "g": gotoBuf = ""; gotoBox.textContent = "go to"; gotoBox.hidden = false; break;
        case "n": deck.classList.toggle("with-notes"); fit(); break;
        case "o": overview.hidden = !overview.hidden; break;
        case "Escape": overview.hidden = true; break;
        case "f":
          if (document.fullscreenElement) document.exitFullscreen();
          else if (deck.requestFullscreen) deck.requestFullscreen();
          break;
        default: return;
      }
      e.preventDefault();
    });
    stage.addEventListener("click", function(e){
      if (e.target.closest("a, button, .sides, pre, code")) return;
      var r = stage.getBoundingClientRect();
      show(i + (e.clientX < r.left + r.width * 0.28 ? -1 : 1));
    });
    window.addEventListener("resize", fit);
    document.addEventListener("fullscreenchange", fit);

    var start = parseInt((location.hash || "").slice(1), 10);
    fit();
    Promise.all(loads).then(function(){ show(isNaN(start) ? 0 : start - 1); });
  }

  document.addEventListener("DOMContentLoaded", function(){
    var deck = document.querySelector("[data-slides]");
    if (!deck) return;
    MMST.text(deck.getAttribute("data-slides")).then(function(src){ build(deck, src); }, function(e){
      deck.querySelector(".stage").textContent = String(e);
    });
  });
})();
