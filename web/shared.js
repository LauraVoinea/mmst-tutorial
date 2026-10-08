/* Shared by all pages: theme, the four checks, highlighting, footer. */
(function(){
  "use strict";

  try {
    var saved = localStorage.getItem("mmst.theme");
    if (saved) document.documentElement.setAttribute("data-theme", saved);
  } catch (e) {}

  var MMST = window.MMST = window.MMST || {};

  MMST.order  = ["WF", "SD", "CT", "BA"];
  MMST.checks = {
    WF: ["well-formed",       "Each label is committing everywhere or nowhere, so a receiver knows which side of the race it is on."],
    SD: ["single-decision",   "On the right, every role depends on the observer: an overrule reaches everyone."],
    CT: ["clear-termination", "On the left, the observer reaches every role, directly or transitively: nobody finishes without knowing which side won."],
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

  MMST.footer = function(){
    var f = document.getElementById("site-footer");
    if (!f) return;
    f.innerHTML =
      '<span><i>Mixed Choice in Asynchronous Multiparty Session Types</i></span>' +
      '<nav><a href="https://doi.org/10.1145/3798256">Paper</a>' +
      '<a href="https://arxiv.org/abs/2602.23927">arXiv</a>' +
      '<a href="https://github.com/rhu1/scribble-gt-scala/tree/artifact">Code</a></nav>';
  };

  document.addEventListener("DOMContentLoaded", function(){
    MMST.footer();
    var b = document.getElementById("theme");
    if (!b) return;
    b.addEventListener("click", function(){
      var root = document.documentElement, cur = root.getAttribute("data-theme");
      var next = cur === "dark" ? "light" : cur === "light" ? "" : "dark";
      if (next) root.setAttribute("data-theme", next);
      else root.removeAttribute("data-theme");
      try { localStorage.setItem("mmst.theme", next); } catch (e) {}
    });
  });
})();
