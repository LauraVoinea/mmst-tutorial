/* Menu examples: which, in what order, what each is. The text is in text/examples.md
   (every "try" there was run against the checker); MMST.examples.ready settles once it is
   read. Used by index.html and examples.html. */
(function(){
  "use strict";
  var MMST = window.MMST = window.MMST || {};

  var E = MMST.examples = {

    // Filled from text/examples.md:
    featured: [],   // the entries under "# Examples", in order: the examples page's cards
    groups: {},     // group name -> its introduction (HTML)
    about: {},      // example id -> { group, text, title, from, what, mc, look, tryIt: [try, result] }

    title: function(id){ var a = E.about[id]; return (a && a.title) || id; },

    // For display: exercises in examples.md order, then the featured examples; with
    // `all`, the rest as "More examples".
    arrange: function(list, all){
      var keys = Object.keys(E.about), names = [], by = {}, out = [];
      function rank(id){ var i = keys.indexOf(id); return i < 0 ? keys.length : i; }
      list.forEach(function(x){
        if (!by[x.group]) { by[x.group] = []; names.push(x.group); }
        by[x.group].push(x);
      });
      names.forEach(function(g){
        var xs = by[g];
        if (g !== "Examples") {
          out.push({ group: g, items: xs.slice().sort(function(a, b){
            return rank(a.id) - rank(b.id) || a.id.localeCompare(b.id); }) });
          return;
        }
        out.push({ group: g, items: E.featured.map(function(id){
          return xs.filter(function(x){ return x.id === id; })[0]; }).filter(Boolean) });
        if (all) out.push({ group: "More examples", items: xs.filter(function(x){
          return E.featured.indexOf(x.id) < 0; }) });
      });
      return out;
    }
  };

  // "# Group", then "## id" entries: a paragraph is the description, a list item
  // "key: value" a field. Values are Markdown, kept as HTML.
  var FIELDS = { "title": "title", "from": "from", "what": "what", "mixed choice": "mc",
                 "look": "look", "try": "try", "result": "result" };
  function read(src){
    var marked = window.marked, group = null, id = null;
    marked.lexer(src).forEach(function(t){
      if (t.type === "heading" && t.depth === 1) { group = t.text.trim(); id = null; return; }
      if (t.type === "heading" && t.depth === 2) {
        id = t.text.trim();
        E.about[id] = { group: group };
        if (group === "Examples") E.featured.push(id);
        return;
      }
      if (t.type === "paragraph") {
        var html = marked.parseInline(t.text);
        if (id) E.about[id].text = E.about[id].text ? E.about[id].text + " " + html : html;
        else if (group && !E.groups[group]) E.groups[group] = html;
        return;
      }
      if (t.type === "list" && id) t.items.forEach(function(it){
        var m = /^([a-z ]+):\s*([\s\S]*)$/i.exec(it.text.trim()), k = m && FIELDS[m[1].toLowerCase()];
        if (k) E.about[id][k] = marked.parseInline(m[2]);
      });
    });
    Object.keys(E.about).forEach(function(id){
      var a = E.about[id];
      if (a["try"]) a.tryIt = [a["try"], a.result || ""];
      delete a["try"]; delete a.result;
    });
    return E;
  }

  E.ready = (MMST.text ? MMST.text("text/examples.md") : Promise.reject(new Error("load shared.js first")))
    .then(read)
    ["catch"](function(e){ if (window.console) console.error(e); return E; });   // the menu still works, by file name
})();
