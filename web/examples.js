/* Menu examples: which, in what order, what each is. Used by index.html and
   examples.html. Every "try" was run against the checker. */
(function(){
  "use strict";
  var MMST = window.MMST = window.MMST || {};

  var E = MMST.examples = {

    // Menu examples, in order; the rest of examples/scribble is listed last on the examples page.
    featured: ["Calculator", "Interrupt", "FailureHandling", "TwoBuyer",
               "CircuitBreaker", "amqp_selective_consumer", "SMTP"],

    // Heading lines on the examples page.
    groups: {
      "Exercise 1": "Start here.",
      "Exercise 2": "Predict which flag fails, then check.",
      "Examples":   "From <code>examples/scribble</code> in scribble-gt-scala, each with a change to try.",
      "More examples": "Also in <code>examples/scribble</code>."
    },

    // Menu order for exercises; others follow by name.
    about: {

      /* exercise 1 */
      "1-Timeout": {
        text: "The paper's Figure 1. B observes: it goes along with A or times out. Four green flags, three local types."
      },
      "1-WebSocketClose": {
        text: "RFC 6455's closing handshake: either side may close first, or both at once. Mirror-image local types."
      },

      /* exercise 2 */
      "2-ClearTermination": { text: "One line of <code>Timeout</code> removed." },
      "2-SingleDecision":   { text: "A different line removed. Two flags fail." },
      "2-WellFormed":       { text: "A label doing two jobs; the checker names it." },
      "2-Balance":          { text: "Not a mixed choice at all." },
      "2-CancellableRPC":   { text: "CT fails: on the left, Server never hears back. Add the messages that tell Server, and Auditor, which side was taken." },
      "2-StreamCancel":     { text: "Valid, but stops after one chunk. Make it recursive so Server streams until Client cancels." },

      /* in the menu */
      "Calculator": {
        from: "Table 1",
        what: "Carol asks Srv for a sum or a difference; the answer goes to Carol, then Alice. Exercise 3 runs its Erlang.",
        mc: "Srv is the observer: it may overrule Carol's <code>sum()</code> or <code>diff()</code> with " +
            "<code>timeout()</code>. Carol tells Alice with <code>cancel()</code>.",
        look: "Carol's EFSM: after <code>sum()</code> she must still take <code>timeout()</code>.",
        tryIt: ["Delete <code>cancel() from Carol to Alice;</code>",
                "<b>SD</b> and <b>BA</b> fail: nobody tells Alice."]
      },
      "Interrupt": {
        from: "In the paper",
        what: "P sends <code>Start()</code>, then streams <code>More()</code>, or sends <code>Stop()</code> and waits for <code>Ack()</code>.",
        mc: "Q is the observer: it may overrule P with <code>Interrupt()</code>. A <code>More()</code> already in flight is stale, and purged.",
        look: "Q's EFSM, then P's: <code>Interrupt()</code> can reach P in every left-hand state.",
        tryIt: ["Delete <code>Ack() from Q to P;</code>",
                "<b>CT</b> fails: after <code>Stop()</code>, P never hears from Q."]
      },
      "FailureHandling": {
        from: "In the paper",
        what: "Master M, worker W, failure detector FD. Each round: <code>HB()</code> to FD, <code>OK()</code> to M, " +
              "<code>result()</code>, <code>more()</code>.",
        mc: "FD is the observer: missing a heartbeat, it overrules W with <code>Timeout()</code> and tells M with <code>Crash()</code>. " +
              "<code>W @failed -&gt; FD</code> is a note: the checks pass without it.",
        look: "W's EFSM: <code>Timeout()</code> can arrive anywhere in its round.",
        tryIt: ["Delete <code>Crash() from FD to M;</code>",
                "<b>SD</b> and <b>BA</b> fail: M waits for a <code>result()</code> that never comes."]
      },
      "TwoBuyer": {
        from: "Table 1",
        what: "Alice asks Seller for a title, both buyers get a quote, Alice tells Bob her share, Bob accepts or rejects.",
        mc: "Two nested mixed choices, both with Seller as observer: it may overrule with <code>not_available()</code> " +
            "in the outer one and <code>response_timeout()</code> in the inner one. Both buyers are told.",
        look: "Seller's EFSM: an entry state per mixed choice.",
        tryIt: ["Delete <code>not_available() from Seller to Bob;</code>",
                "<b>SD</b> and <b>BA</b> fail: Bob waits for a quote."]
      },
      "CircuitBreaker": {
        from: "Table 1",
        what: "Usr calls an API, which asks a Controller: <code>service_operational()</code>, <code>error_notice()</code> " +
              "or <code>shutdown_api()</code>. Storage follows.",
        mc: "The API is the observer: it may overrule the Controller with <code>timeout()</code>; the Controller tells Storage, " +
              "the API tells Usr, and the loop repeats.",
        look: "The comments: three acks for clear termination, <code>cancel_ack()</code> for projection onto Storage.",
        tryIt: ["Delete <code>shutdown_ack() from API to Controller;</code>",
                "<b>CT</b> fails: the Controller finishes without hearing from the API."]
      },
      "amqp_selective_consumer": {
        title: "AMQP selective consumer",
        from: "RabbitMQ case study",
        what: "From RabbitMQ's Erlang client: Consumer subscribes via Channel; Server delivers until someone cancels. " +
              "The completed generated Erlang passes RabbitMQ's tests.",
        mc: "Any of the three may cancel: Server by an ordinary choice, Channel as observer of the outer mixed choice " +
            "(while Server decides), Consumer as observer of the inner one (while a delivery is in flight).",
        look: "Channel's EFSM: both mixed choices inside the loop.",
        tryIt: ["Delete <code>process_message() from Channel to Consumer;</code>",
                "Checks pass; projection onto Consumer fails: nothing tells it the Server's choice."]
      },
      "SMTP": {
        from: "Table 1",
        what: "A mail session: <code>Ehlo()</code>, optional <code>StartTls()</code>, <code>Auth()</code>, then " +
              "<code>Mail()</code>, <code>Rcpt()</code>, <code>Data()</code> repeatedly, <code>Quit()</code>. 31 states per role.",
        mc: "S is the observer of two mixed choices, each a timeout: before the first command, and before <code>Data()</code>.",
        look: "The EFSM tab, and the <code>…Commit</code> labels, marked <code>!!!</code>.",
        tryIt: ["Rename <code>QuitCommit</code> to <code>Quit</code>.",
                "<b>WF</b> fails: <code>Quit</code> does two jobs. The <code>Commit</code> labels keep them apart."]
      },

      /* the rest */
      "DiffTwoBuyer": {
        text: "TwoBuyer, but when the title is unavailable Seller tells Alice, and Alice tells Bob."
      },
      "DistributedLogging": {
        from: "Table 1",
        text: "A controller and a logger in a loop; the controller may time the logger out and restart it."
      },
      "Fibonacci": {
        from: "Table 1",
        text: "Fibonacci numbers back and forth until <code>a</code> stops; <code>b</code> may answer " +
              "<code>error()</code>. Delete the commented <code>ack()</code>: <b>CT</b> fails."
      },
      "OnlineWallet": {
        from: "Table 1",
        text: "Log in, then pay or quit, in a loop. The server may time the client out, and tells the authenticator."
      },
      "SimpleVoting": {
        text: "Authenticate, then vote <code>Yes()</code> or <code>No()</code>; the server may answer " +
              "<code>error()</code>. The mixed choice is in one branch of an ordinary choice."
      },
      "Timeout": {
        from: "Figure 1",
        text: "Figure 1 as in the artifact; <code>1-Timeout</code> is the same, with comments."
      },
      "TravelAgency": {
        from: "Table 1",
        text: "Accept, reject or resubmit a quote, in a loop. The agency may adjust the price; then the client " +
              "cancels with both."
      }
    },

    title: function(id){ var a = E.about[id]; return (a && a.title) || id; },

    // For display: exercises in `about` order, then the featured examples; with
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
})();
