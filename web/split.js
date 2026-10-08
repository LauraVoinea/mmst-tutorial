/* Resizable panes: the .gutter between a main's two panes. Drag, arrow keys
   (Shift: bigger steps), Home/End; double-click or Enter resets. Side by side it
   sets --split (left pane, % of width); stacked (narrow) it sets --top (px).
   Remembered per main in localStorage. */
(function(){
  "use strict";

  var NARROW = window.matchMedia("(max-width:900px)");
  var MIN_LEFT = 260, MIN_RIGHT = 320, MIN_TOP = 160;

  function load(k){ try { var v = parseFloat(localStorage.getItem(k)); return isFinite(v) ? v : NaN; } catch (e) { return NaN; } }
  function save(k, v){ try { if (isFinite(v)) localStorage.setItem(k, String(v)); else localStorage.removeItem(k); } catch (e) {} }
  function clamp(v, lo, hi){ return Math.max(lo, Math.min(hi, v)); }

  function setup(main){
    var g = main.querySelector(":scope > .gutter");
    if (!g) return;
    var kCols = "mmst.split." + main.id, kRows = kCols + ".top";
    var pct = load(kCols), top = load(kRows);

    function bounds(){
      var w = main.clientWidth || window.innerWidth;
      var lo = MIN_LEFT / w * 100, hi = 100 - MIN_RIGHT / w * 100;
      return lo < hi ? [lo, hi] : [50, 50];
    }
    function maxTop(){ return Math.max(MIN_TOP, window.innerHeight * 0.85); }

    function apply(){
      var rows = NARROW.matches;
      if (isFinite(pct)) { var b = bounds(); pct = clamp(pct, b[0], b[1]); main.style.setProperty("--split", pct.toFixed(2)); }
      else main.style.removeProperty("--split");
      if (isFinite(top)) { top = clamp(top, MIN_TOP, maxTop()); main.style.setProperty("--top", Math.round(top) + "px"); }
      else main.style.removeProperty("--top");
      g.setAttribute("aria-orientation", rows ? "horizontal" : "vertical");
      g.setAttribute("aria-valuemin", rows ? MIN_TOP : Math.round(bounds()[0]));
      g.setAttribute("aria-valuemax", rows ? Math.round(maxTop()) : Math.round(bounds()[1]));
      g.setAttribute("aria-valuenow", rows ? Math.round(isFinite(top) ? top : firstHeight())
                                           : Math.round(isFinite(pct) ? pct : 50));
    }
    function firstHeight(){ var p = main.querySelector(":scope > .pane"); return p ? p.getBoundingClientRect().height : 0; }
    function reset(){ if (NARROW.matches) top = NaN; else pct = NaN; apply(); save(kCols, pct); save(kRows, top); }

    g.addEventListener("pointerdown", function(e){
      if (e.button !== 0) return;
      e.preventDefault();
      g.focus();
      g.setPointerCapture(e.pointerId);
      var rows = NARROW.matches, r = main.getBoundingClientRect();
      document.body.classList.add("resizing", rows ? "rows" : "cols");
      function move(ev){
        if (rows) top = ev.clientY - r.top;
        else pct = (ev.clientX - r.left) / r.width * 100;
        apply();
      }
      function up(ev){
        g.removeEventListener("pointermove", move);
        g.removeEventListener("pointerup", up);
        g.removeEventListener("pointercancel", up);
        try { g.releasePointerCapture(ev.pointerId); } catch (err) {}
        document.body.classList.remove("resizing", "rows", "cols");
        save(kCols, pct); save(kRows, top);
      }
      g.addEventListener("pointermove", move);
      g.addEventListener("pointerup", up);
      g.addEventListener("pointercancel", up);
    });

    g.addEventListener("keydown", function(e){
      var rows = NARROW.matches, k = e.key;
      var fwd = rows ? "ArrowDown" : "ArrowRight", back = rows ? "ArrowUp" : "ArrowLeft";
      if (k === "Enter") { reset(); e.preventDefault(); return; }
      if (k !== fwd && k !== back && k !== "Home" && k !== "End") return;
      e.preventDefault();
      if (rows) {
        var t = isFinite(top) ? top : firstHeight(), dy = e.shiftKey ? 96 : 24;
        top = k === "Home" ? MIN_TOP : k === "End" ? maxTop() : t + (k === fwd ? dy : -dy);
      } else {
        var p = isFinite(pct) ? pct : 50, dx = e.shiftKey ? 10 : 2, b = bounds();
        pct = k === "Home" ? b[0] : k === "End" ? b[1] : p + (k === fwd ? dx : -dx);
      }
      apply(); save(kCols, pct); save(kRows, top);
    });

    g.addEventListener("dblclick", reset);
    if (NARROW.addEventListener) NARROW.addEventListener("change", apply);
    window.addEventListener("resize", apply);
    apply();
  }

  Array.prototype.forEach.call(document.querySelectorAll("main"), setup);
})();
