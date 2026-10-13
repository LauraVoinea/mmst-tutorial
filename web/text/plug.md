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

## Session types

A session type describes a protocol: who sends which message to whom, and in what order.

- Checked statically: an implementation that type checks follows the protocol.
- Binary session types: two parties. 
- **Multiparty** session types (MST): any number of roles.
    - **Global type:** the protocol for all roles.
    - **Local types:** one role's obligations, obtained by *projection*.
- Typical guarantees: no communication errors, no deadlocks, and the implementation follows the protocol.

---

## Multiparty session types

A global protocol, projected onto one local type per role; `⊕` select/send, `&` branch/receive.

```types
Global:  Client → Shop:order . Shop → Client:receipt . end
```

```types
Client:  Shop ⊕ order . Shop & receipt . end
Shop:    Client & order . Client ⊕ receipt . end
```


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
Client:  Shop ⊕ {buy: end, cancel: end}       // a select: output only
Shop:    Client & {buy: end, cancel: end}     // a branch: input only
```

---

## A timeout example

A process, say **B**, may **receive** the expected message or **send** a timeout notification.

```erlang
receive
    {A, a1} -> normal_continuation()
after 5000 ->
    A ! {self(), TOa},
    timeout_continuation()
end.
```

**What if A has already sent `a1`, but the message has not yet reached B?**

Note: This is schematic Erlang-style code: normal_continuation/0 and timeout_continuation/0 stand for application callbacks. A is the peer PID. Scheduling and transit delays can let B's local timeout fire even though A has sent the normal message.

---
## A timeout example

```text
A                               B
| --- a1 (normal) ----------->  |
|  <------------ TOa (timeout) -|
|                               |
```

- A has sent the normal message `a1`.
- B times out and sends `TOa` **before receiving** `a1`.
- For a while, the two processes behave as though different branches are active.

**A directed choice does not describe this race.**

Note: Both sends can occur before either corresponding receive; these are messages in flight, not an assertion that one endpoint has made a compatible commitment. Distinguish sending from receiving. 

---

## Inconsistency as a feature

Distributed participants do not always learn about a decision simultaneously.

**The question is whether their local behaviours can still converge on one consistent outcome.**

- Allow temporary disagreement about the branch being followed.
- Rule out **conflicting commitments** to opposite branches.
- Deal with messages sent on the branch that was not ultimately taken.

Note: Temporary divergence of behaviour is allowed; it is not a licence for incompatible commitments. This distinction drives the observer and commitment mechanism introduced next.

---

## Timeout

```types
A → B:a1 . A → C:a2 . B → C:a3 . B → A:a4 . C → A:a5 . end
  ⊳  B → A:TOa . B → C:TOc . end                          
```

Normal (left) behaviour:

```types
A → B:a1 . A → C:a2 . B → C:a3 . B → A:a4 . C → A:a5 . end
```

Timeout (right) behaviour:

```types
B → A:TOa . B → C:TOc . end
```

The mixed choice is written **left `⊳` right**. B is the **observer**: it can continue normally or trigger the timeout branch.

Note: These are the same branches as the paper's Timeout example. On the left A first sends to B and C; on the right B notifies A and C of the timeout. Explain the normal branch in one sentence, not message by message. The next slide explains why B is special.

---

## The observer

- The **left branch** is the default behaviour.
- The **observer B** is the only role that can initiate the right branch.
- B commits to the **left** by receiving `a1`.
- Alternatively, B commits to the **right** by sending `TOa`.

**The construct is asymmetric:** its right branch can overrule the left.

Note: The mixed-choice pattern makes the direction of the race explicit: A→B on the left, B→A on the right. Contrast with symmetric mixed choice in synchronous settings. Do not imply that other roles have already committed merely because B has.

---

## What can go wrong

Roles act on different sides for a while. Without checks:

- a role finishes without learning which side was taken;
- a role never learns that the other side was taken;
- a label commits a role in one place and not in another;
- a role waits for a message that only one branch sends;
- messages from the side not taken stay in queues.

The first four are excluded statically, by four checks. The last is handled at run time.

---

## Timeout in Scribble

```scribble
global protocol Timeout(role A, role B, role C) {
    mixed {                       // left
        a1() from A to B;
        a2() from A to C;
        a3() from B to C;
        a4() from B to A;
        a5() from C to A;
    } or A -> B {                 // right: B overrules A
        TOa() from B to A;
        TOc() from B to C;
    }
}
```

[Playground](./#ex=1-Timeout)

---

## Commitment

- When a mixed choice starts, no role is committed to either side.
- The observer commits with its first action: a receive on the left, or a send on the right.
- Any other role commits with its first action that causally depends on the observer's.
- Commitments only grow, and always agree with the observer.

---

## Committing actions

Definition 3.1, the committing set of a mixed choice:

- on the left, the observer's first receive, and every receive of a message from a committed sender;
- on the right, the first action of each role.

Actions are identified by their labels. In Timeout: `a1`, `a3`, `a4`, `a5` on the left; `TOa`, `TOc` on the right.

---

## Two runs

<div data-include="parts/two-runs.html"></div>

{.small} Left: B commits by receiving `a1`, C by receiving `a3`, A by receiving `a4`. Right: B commits by sending `TOa`, A and C by receiving `TOa` and `TOc`.

Note: The dots fill as each role commits. Switch between the two runs with the buttons.

---

## Stale messages

- On the right, `a1` and `a2` were sent on the side not taken: they are **stale**.
- B purges `a1` when it arrives; C purges `a2`.
- Each runtime decides this from local information only.
- In the semantics, rule [Discard] removes stale messages from a role's queue. The paper compares this to garbage collection.

---

## The four checks

- **WF**, well-formed: each label is committing everywhere or nowhere (Definition 3.4).
- **SD**, single-decision: on the right, every role depends on the observer (Definition 3.9).
- **CT**, clear-termination: on the left, a role receives a committing message before it terminates (Definition 3.9).
- **BA**, balanced: the branches of a choice involve the same roles (Definition 3.12).

SD and CT together are **awareness**. Theorem 3.13: an initial, aware and balanced global type enjoys **progress**.

---

## Local types for Timeout

```types
A:  B⊕a1 . C⊕a2 . B&a4 . C&a5 . end   ⊳  B&TOa . end
B:  A&a1 . C⊕a3 . A⊕a4 . end          ⊳  A⊕TOa . C⊕TOc . end
C:  A&a2 . B&a3 . A⊕a5 . end          ⊳  B&TOc . end
```

- `⊕` a select, an output-only choice; `&` a branch, an input-only choice; `⊳` separates the left and right sides.
- A and B are not duals: B decides to time out; A has no matching action.

---

## Local behaviour

- A role runs the left side until it commits.
- A committing receive, or the observer's own send on the right, decides its side.
- A role may face input and output in the same state: B in its first state may receive `a1` or send `TOa`.
- Each role has a queue of incoming messages; stale ones are purged from it.

---

## Correspondence and local properties

For a system projected from an initial, aware and balanced global type:

- **Operational correspondence** (Theorem 4.4): the global type and its projection match step for step, modulo stale messages.
- **Local progress** (Corollary 4.6): every role that has not finished can eventually act.
- **Orphan-message freedom** (Theorem 4.7): every message in a queue can eventually be received, unless it becomes stale.

---

## Protocol specification and validation

- [Timeout](./#ex=1-Timeout): check it; all four checks pass.
- Broken variants, one change each: [a](./#ex=2-a) and [b](./#ex=2-b) are Timeout without `a3` and without `TOc`; then [c](./#ex=2-c) and [d](./#ex=2-d).
- A failing check names the mixed choice and the lines involved.

Note: Press Check. a is Timeout without a3: CT fails, and the editor marks the lines. b fails SD and BA, c fails WF, and d, an ordinary choice, fails BA.

---

## Global to local projection

- After the checks, the local types, one per role: [Timeout](./#ex=1-Timeout).
- A protocol can pass all four checks and still fail projection. Example: in [AMQP selective consumer](./#ex=amqp_selective_consumer), delete `process_message() from Channel to Consumer;`: nothing tells Consumer the Server's choice.
- This is the usual merge failure of MST, not specific to mixed choice.

---

## From a global protocol to runnable roles

```text
          Scribble global protocol
                     │
             Static checks
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

**Generate the protocol mechanics; let developers supply application logic.**

Note: Implementation relies on Erlang/OTP gen_statem. The checks specific to mixed choice do not obviate ordinary projection constraints.

---
## Mixed choice based code generation

Erlang's built-in `gen_statem` behaviour:

- a generic framework to implement event-driven finite state machines, EFSMs;
- developers customise it by writing callback functions.

```
State (S) × Event (E) → Action (A), State (S')
```

In a state S, the arrival of an event E triggers an action A, for example sending a message, and a transition to a new state S'.

---

## EFSMs for local types

<div class="cols"><div>

**Events (E)**

- Message arrival: `B?a4`, role B's message `a4` received.
- Internal decisions `τ`: local triggers such as timeouts or local computations.

</div><div>

**Actions (A)**

- Sending messages: `B!a1`, send message `a1` to role B.
- Sending and switching `!*`: switch from the left side, the normal flow, to the right, timeout handling.

</div></div>

Example: `B?*TOa` at role A. Timeout message `TOa` received from B triggers switching to timeout handling.

[The EFSMs for Timeout](./#ex=1-Timeout&view=efsm)

---

## Generated code structure

The toolchain generates two Erlang modules for each role.

<div class="cols"><div>

**`gen_role`**, the generic behaviour:

- defines the EFSM structure;
- handles the protocol mechanics: messages, states, mixed choice;
- typically not edited by users.

</div><div>

**`role`**, the callback implementation:

- contains the callback functions with custom logic;
- defines application-specific behaviour, such as timeouts and conditions;
- written by the user, on top of `gen_role`.

</div></div>

---

## Interactions between modules

How `gen_role` and `role` work together:

1. **Event received**: messages and internal events `τ` arrive at the `gen_role` state machine.
2. **Delegation to callback**: `gen_role` forwards events to the callback module `role` for decision logic.
3. **Callback response**: `role` decides on actions and returns instructions to `gen_role`.
4. **State transition and actions**: `gen_role` executes them: it sends outgoing messages and purges stale messages from the event queue.

---

## Summary

- Mixed choice in asynchronous MST: an asymmetric construct with an observer.
- Commitment decides each role's side; stale messages are purged locally.
- Four checks: well-formedness, awareness (single-decision and clear-termination), balance. They give progress.
- Projection: operational correspondence modulo stale messages, and orphan-message freedom.
- A toolchain from Scribble protocols to Erlang `gen_statem` modules.

{.small} Paper: [doi.org/10.1145/3798256][doi] · extended version: [arXiv:2602.23927][arxiv] · playground: [this site](./) · code: [scribble-gt-scala][code]

[doi]: https://doi.org/10.1145/3798256
[arxiv]: https://arxiv.org/abs/2602.23927v2
[code]: https://github.com/rhu1/scribble-gt-scala/tree/artifact
