/* The two runs (parts/two-runs.html): a tab replays the left or the right run, and the
   dot on a role's line fills when that role commits. Starts once MMST.ready settles;
   on the slides, a run replays when its slide is shown. */
(function(){
  "use strict";
  var MMST = window.MMST = window.MMST || {};

  // When each role commits, in ms from the start of the run.
  var COMMITS = {
    lhs: [["B", 550], ["C", 1000], ["A", 1450]],   // B on a1, C on a3, A on a4
    rhs: [["B", 150], ["A", 550], ["C", 750]]      // B on TOa, A on TOa, C on TOc
  };

  function each(list, f){ Array.prototype.forEach.call(list, f); }

  function setup(box){
    if (box.sidesPlay) return;
    var svg = box.querySelector(".sides-diagram"), timers = [];
    if (!svg) return;
    function play(run){
      timers.forEach(clearTimeout); timers = [];
      each(box.querySelectorAll(".sides-tab"), function(b){ b.classList.toggle("on", b.dataset.run === run); });
      each(svg.querySelectorAll(".sides-run"), function(g){ g.classList.remove("show"); });
      each(svg.querySelectorAll(".commit"), function(c){ c.classList.remove("on"); });
      void svg.getBoundingClientRect();                       // reflow: replay the draw-in
      var group = svg.querySelector(".sides-run." + run);
      if (group) group.classList.add("show");
      (COMMITS[run] || []).forEach(function(c){
        timers.push(setTimeout(function(){
          var dot = svg.querySelector('.commit[data-role="' + c[0] + '"]');
          if (dot) dot.classList.add("on");
        }, c[1]));
      });
    }
    each(box.querySelectorAll(".sides-tab"), function(b){
      b.addEventListener("click", function(){ play(b.dataset.run); });
    });
    box.sidesPlay = play;
    play("lhs");
  }

  MMST.sides = function(root){ each((root || document).querySelectorAll(".sides"), setup); };

  if (MMST.ready) MMST.ready.then(function(){ MMST.sides(); });
  document.addEventListener("mmst:slide", function(e){        // from slides.js
    each(e.detail.querySelectorAll(".sides"), function(box){
      setup(box);
      var on = box.querySelector(".sides-tab.on");
      box.sidesPlay(on ? on.dataset.run : "lhs");
    });
  });
})();
