<!-- The PLUG talk, shown by plug.html (engine: slides.js).
     Slides are separated by a line "---" with a blank line before it.
     "{.title}" or "{.part}" on a slide's first line sets its layout.
     "Note:" starts the speaker notes (key n), to the end of the slide.
     <div data-include="parts/two-runs.html"></div> pulls in the two-runs diagram (once only).
     Links open in a new tab; ./#ex=NAME opens a protocol in the playground. -->

{.title}
# Mixed choice in asynchronous multiparty session types

Laura Bocchi, Raymond Hu, **Laura Voinea**, Simon Thompson

{.small} PLUG 2026

---

## Multiparty session types

A **global type** describes a protocol for all roles: who sends which message to whom, and in what order. *Projection* gives one **local type** per role; `⊕` select/send, `&` branch/receive.

```types
Global:  Client → Shop:order . Shop → Client:receipt . end
```

```types
Client:  Shop⊕order . Shop&receipt . end
Shop:    Client&order . Client⊕receipt . end
```

- Typical guarantees: no communication errors, no deadlocks, and each role follows the protocol.
- Usually each implementation is type checked against its local type. In this talk, the protocol part of each role is generated from it instead.

Note: Keep this to a minute for a PL audience. Plant two things: projection, and that our guarantees will come from generated code, not from type checking Erlang.

---

## Directed choice

In classical MST, one role decides, and at each choice a role either only sends or only receives.

```scribble
choice at Client {
    buy() from Client to Shop;
} or {
    cancel() from Client to Shop;
}
```

```types
Client:  Shop⊕{buy: end, cancel: end}         // a select: output only
Shop:    Client&{buy: end, cancel: end}       // a branch: input only
```

---

## A timeout example

B may **receive** `a1`, or time out and **send** `TOa`.

<div class="cols"><div>

```erlang
receive
    {A, a1} -> normal_continuation()
after 5000 ->
    A ! {self(), 'TOa'},
    timeout_continuation()
end.
```

</div><div>

```text
A                               B
| --- a1 (normal) ----------->  |
|  <------------ TOa (timeout) -|
|                               |
```

</div></div>

- A has sent `a1`, but B times out before it arrives and sends `TOa`. For a while, A and B act on different sides of the choice.
- **A directed choice cannot describe this: at the same point, B may receive or send.**

Note: Schematic Erlang: normal_continuation/0 and timeout_continuation/0 stand for application callbacks, and A is the peer PID. Scheduling and transit delays can let B's timeout fire after A has sent a1. Both sends can happen before either receive: these are messages in flight, not commitments. Distinguish sending from receiving.

---

## Inconsistency as a feature

Roles in a distributed system do not always learn of a decision at the same time.

**The question is whether their local behaviours can still converge on one consistent outcome.**

- Allow temporary disagreement about the side being followed.
- Rule out **conflicting commitments** to opposite sides.
- Deal with messages sent on the side that was not taken.

The same shape comes up in interrupts, cancellation and exceptions, not only in timeouts.

Note: Temporary divergence of behaviour is allowed; it is not a licence for incompatible commitments. This distinction drives the observer and commitment.

---

## From a global protocol to runnable roles

<div class="cols"><div>

```text
      Scribble global protocol
                 │
         Safety checks
         WF · SD · CT · BA
                 │
             Projection
                 │
           Local types
                 │
         EFSM generation
                 │
            Erlang code
         gen_role + role
```

</div><div>

**Generate the protocol mechanics; let developers supply application logic.**

The rest of the talk follows it from the top: a protocol with a mixed choice and what it means, the safety checks, projection, then the generated code.

</div></div>

Note: A map for the rest of the talk. Implementation relies on Erlang/OTP gen_statem.

---

## Mixed choice

The timeout example as a protocol with a third role, C: in Scribble, and as a global type written **left `⊳` right**.

<div class="cols"><div>

```scribble
mixed {         // left
    a1() from A to B;
    a2() from A to C;
    a3() from B to C;
    a4() from B to A;
    a5() from C to A;
} or A -> B {   // right
    TOa() from B to A;
    TOc() from B to C;
}
```

</div><div>

```types
  A → B:a1 . A → C:a2 .
  B → C:a3 . B → A:a4 .
  C → A:a5 . end
⊳ B → A:TOa . B → C:TOc . end
```

</div></div>

In `or A -> B`, B is the **observer**: it may overrule A. [Playground](./#ex=1-Timeout)

Note: These are the same sides as the paper's Timeout example. On the left A first sends to B and C; on the right B notifies A and C of the timeout. Explain the left side in one sentence, not message by message. The next slide explains why B is special.

---

## The observer

- The **left side** is the default behaviour.
- The **observer B** is the only role that can initiate the right side.
- B commits to the **left** by receiving `a1`.
- Alternatively, B commits to the **right** by sending `TOa`.

**The construct is asymmetric:** its right side can overrule the left. That keeps it simple enough to prove progress and correspondence.

Note: `or A -> B` makes the direction explicit: A→B on the left, B→A on the right. Contrast with symmetric mixed choice in synchronous settings. Do not imply that other roles have already committed merely because B has. If asked why asymmetric: it is practical and simple, and the paper already lifts it to a more general postponed commitment.

---

## Commitment

- When a mixed choice starts, no role is committed to either side.
- The observer commits with its first action: a receive on the left, or a send on the right.
- Any other role commits with its first action that causally depends on the observer's.
- Commitments only grow, and always agree with the observer.

{.small} Definition 3.1, the committing set: on the left, the observer's first receive, then each other role's first receive from a role that has committed; on the right, each role's first action. In Timeout: `a1`, `a3`, `a4` on the left, `TOa`, `TOc` on the right.

Note: Actions are identified by their labels. a2 and a5 are not committing: A sends a2 before A has committed, and when a5 reaches A, A has already committed on a4.

---

## Two runs

<div data-include="parts/two-runs.html"></div>

{.small} Left: B commits by receiving `a1`, C by receiving `a3`, A by receiving `a4`. Right: B commits by sending `TOa`, A and C by receiving `TOa` and `TOc`.

Note: The dots fill as each role commits. Switch between the two runs with the buttons.

---

## Stale messages

- If B overrules after A has sent `a1` and `a2`, both belong to the side not taken: they are **stale**.
- B purges `a1` when it arrives.
- C purges `a2` if `TOc` gets there first. If `a2` arrives first, C takes it: `a2` is not committing, so C still switches on `TOc`.
- Each role decides this from local information only.
- In the semantics, rule [Discard] removes stale messages from a role's queue. The paper compares this to garbage collection.

Note: In the run shown, TOc reaches C before a2. Until recently our generator committed C on a2, then purged TOc as stale, and C waited for ever: the committing set matters to the runtime, not only to the proofs.

---

## What the safety checks rule out

| Without checks | Ruled out by |
|---|---|
| a label commits a role in one place and not in another | **WF**, well-formed: each label is committing everywhere or nowhere |
| a role never learns that the other side was taken | **SD**, single-decision: on the right, every role depends on the observer |
| a role finishes without learning which side was taken | **CT**, clear-termination: on the left, a role receives a committing message before it terminates |
| a role waits for a message that only one side sends | **BA**, balanced: the sides or branches of a choice involve the same roles |
| messages from the side not taken stay in queues | at run time: each role purges stale messages |

{.small} WF is Definition 3.4; SD and CT are Definition 3.9, together **awareness**; BA is Definition 3.12. Theorem 3.13: an initial, aware and balanced global type enjoys **progress**.

Note: Plainer readings. SD: if the observer overrules, the news reaches everybody. CT: the observer reaches every other role, directly or through someone else. The flags are not independent: one deleted line can turn two red.

---

## Checking Timeout, then breaking it

- [Timeout](./#ex=1-Timeout): all four checks pass.
- [a](./#ex=2-a) is Timeout without `a3`; [b](./#ex=2-b) is Timeout without `TOc`.
- **Before pressing Check: which check fails, and for whom?**
- [c](./#ex=2-c) and [d](./#ex=2-d) make one change each too. A failing check names the mixed choice and the lines involved.

Note: Ask for predictions on a and b before pressing Check. a: CT fails, for C: without a3, C never receives a committing message on the left. b: SD and BA fail, for C: nobody tells C on the right, where C no longer appears. c fails WF, and d, an ordinary choice, fails BA. The editor marks the lines.

---

## Projection

- After the safety checks, projection gives one local type per role: [Timeout](./#ex=1-Timeout).
- Projection keeps the usual MST conditions, for every choice. Here nothing tells Courier what Client chose:

<div class="cols"><div>

```scribble
choice at Client {
    buy() from Client to Shop;
    ship() from Courier to Shop;
} or {
    cancel() from Client to Shop;
    idle() from Courier to Shop;
}
```

</div><div>

[Check it](./#p=bW9kdWxlIE9yZGVyOwoKZ2xvYmFsIHByb3RvY29sIE9yZGVyKHJvbGUgQ2xpZW50LCByb2xlIFNob3AsIHJvbGUgQ291cmllcikgewogICAgY2hvaWNlIGF0IENsaWVudCB7CiAgICAgICAgYnV5KCkgZnJvbSBDbGllbnQgdG8gU2hvcDsKICAgICAgICBzaGlwKCkgZnJvbSBDb3VyaWVyIHRvIFNob3A7CiAgICB9IG9yIHsKICAgICAgICBjYW5jZWwoKSBmcm9tIENsaWVudCB0byBTaG9wOwogICAgICAgIGlkbGUoKSBmcm9tIENvdXJpZXIgdG8gU2hvcDsKICAgIH0KfQo): the four checks pass, but projection onto Courier fails. Courier sends in a branch before it can know which branch was taken.

</div></div>

Note: The safety checks do not replace projection's own conditions. The playground says: Checks pass, but projection onto Courier fails. The fix is the usual one: Client tells Courier, with buy() and cancel() from Client to Courier.

---

## Local types for Timeout

```types
A:  B⊕a1 . C⊕a2 . B&a4 . C&a5 . end   ⊳  B&TOa . end
B:  A&a1 . C⊕a3 . A⊕a4 . end          ⊳  A⊕TOa . C⊕TOc . end
C:  A&a2 . B&a3 . A⊕a5 . end          ⊳  B&TOc . end
```

- A role runs the left side until it commits. Until then, the right side's committing receive stays enabled.
- A state may offer input and output: B may receive `a1` or send `TOa`; A may send `a1` or receive `TOa`.
- A and B are not direct duals: A can wait for `a4` or `TOa`, but B never chooses between sending them.
- Each role has a queue of incoming messages; stale ones are purged from it.

Note: `⊕` a select, an output-only choice; `&` a branch, an input-only choice; `⊳` separates the left and right sides. The duals point is from the paper's discussion of Figure 2. Because the right side's receive stays enabled, C can take a2 and still switch on TOc.

---

## Properties of the projected system

For a system projected from an initial, aware and balanced global type:

- **Coherence**: at no point are two roles committed to different sides of a mixed choice.
- **Operational correspondence**, Theorem 4.4: the global type and its projection match step for step, modulo stale messages.
- **Local progress**, Corollary 4.6: every role that has not finished can eventually act.
- **Orphan-message freedom**, Theorem 4.7: every message in a queue can eventually be received, unless it becomes stale.

Note: Coherence answers the question from Inconsistency as a feature. In the paper it appears in Section 3.3, in the proof of progress: all committed roles are committed to the same side. The paper also calls the correspondence fidelity, in Lemmas 4.1 and 4.2.

---

## EFSMs and gen_statem

Back to the pipeline. Each local type becomes an EFSM, an event-driven finite state machine, run by Erlang's `gen_statem`: in state S, event E triggers action A and a move to state S'.

<div class="cols"><div>

**Events (E)**

- Message arrival: `B?a4`, role B's message `a4` received.
- Internal decisions `τ`: local triggers such as timeouts or local computations.

</div><div>

**Actions (A)**

- Sending messages: `B!a1`, send message `a1` to role B.
- Switching `*`: moving to the right side, when sending, `A!*TOa` at B, or when receiving, `B?*TOa` at A.

</div></div>

[The EFSMs for Timeout](./#ex=1-Timeout&view=efsm)

Note: The playground shows B's switch as τ / A!*TOa() and A's as B?*TOa(), as in the paper's Figure 2.

---

## Two modules per role

The toolchain generates two Erlang modules for each role.

<div class="cols"><div>

**`gen_role`**, the generic behaviour:

- defines the EFSM structure;
- handles the protocol mechanics: messages, states, commitment, stale messages;
- typically not edited by users.

</div><div>

**`role`**, the callback implementation:

- contains the callback functions with custom logic;
- defines application-specific behaviour, such as timeouts and conditions;
- written by the user, on top of `gen_role`.

</div></div>

An event arrives at `gen_role`, which asks `role` what to do, then sends, changes state, records commitment and purges stale messages.

---

## Mixed choice, in code

`gen_b.erl`, B's mixed-choice entry state, abridged:

```erlang
s5(internal, {'TOa'}, Data) ->            % B may time out...
    after_transition(CallbackModule:s5(internal, {'TOa'}, Data));
s5(cast, {APid, {a1}, Path}, Data) ->     % ...or a1 may arrive
    case message_status(Path, branch_path(mc1, left)) of
      stale     -> {keep_state, Data};    % sent on the side not taken
      ready     -> ... commit_if_taken(Next, mc1, left | right) ...;
      not_ready -> {keep_state, Data, [postpone]}
    end.
```

One state function, a send and a receive: in classical MST a state either sends or receives.

Note: Path is the list of sides the sender was on when it sent the message. stale: the sender was on a side this role has ruled out, so the message is purged. not_ready: this role has not reached the sender's side yet, so the message is postponed. ready: the callback decides, and commit_if_taken records the side it took. On a1, B's callback may still send TOa: the EFSM edge A?a1()/A!*TOa().

---

## Running it

[Timeout, in the playground's Erlang mode](./#mode=erlang&erl=examples/timeout): the generated `gen_` modules with filled-in callbacks.

- Runs differ: the interleaving changes, and B's callback chooses at random whether to time out.
- Some runs take the left side, some the right; every role finishes either way.
- As long as the generated `gen_` modules are not edited, coherence is still guaranteed.

Note: Check this on the deployed playground before the talk. Run it two or three times so both sides show up: B prints s6 Sending a3 on the left and s3 Sending TOc on the right. Do not promise a purge on screen: none showed in 20 runs here.

---

## RabbitMQ

- `amqp_selective_consumer`, a module of RabbitMQ's Erlang client, follows [a protocol](./#ex=amqp_selective_consumer) with a recursive mixed choice: deliver another message, or cancel. The consumer, the channel and the server can each cancel.
- The toolchain generates `gen_consumer` for the Consumer role. With its callbacks written as `amqp_selective_consumer.erl`, the pair replaces RabbitMQ's module, and the client's selective-consumer tests pass.

Note: The paper's case study. In the artifact: README section 4.1.2; ./mMST.sh -run-erlang-examples runs these tests.

---

## Limits

- No delegation: a role cannot hand its session to another process.
- Projection is syntactic: it rejects some protocols that would be safe.
- Erlang only, so far; the theory itself is not specific to Erlang.
- Payloads are not checked: the generated code accepts any term.

Note: A safe protocol projection rejects: in Calculator, rename diff_result to sum_result. Alice then gets sum_result in both branches and ends either way, but projection rejects it. The paper's Section 6 covers limitations and related work.

---

## Summary

- Mixed choice in asynchronous MST: an asymmetric construct with an observer.
- Commitment decides each role's side; stale messages are purged locally.
- Four safety checks: well-formedness; single-decision and clear-termination, together awareness; balance. They give progress.
- Projection: coherence, operational correspondence modulo stale messages, local progress and orphan-message freedom.
- A toolchain from Scribble protocols to Erlang `gen_statem` modules, used on RabbitMQ's client.

{.small} Paper: [doi.org/10.1145/3798256][doi] · extended version: [arXiv:2602.23927][arxiv] · playground: [this site](./) · code: [scribble-gt-scala][code]

[doi]: https://doi.org/10.1145/3798256
[arxiv]: https://arxiv.org/abs/2602.23927v2
[code]: https://github.com/rhu1/scribble-gt-scala/tree/artifact
