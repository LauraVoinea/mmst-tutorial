<!-- Descriptions for the examples page and the playground's menu, read by examples.js.
     # a group: its first paragraph introduces it.
     ## an example, by file name. A paragraph is its one-line description; a list sets fields:
       title (menu name), from (source), what, mixed choice, look, try and result.
     Order matters: the exercises and the protocols from the slides appear in this order, and
     the entries under # Examples are the examples page's cards, in this order. -->

# Exercise 1

Two valid protocols: check each, then read its local types, one per role.

## 1-Timeout

The paper's Figure 1. B observes: it goes along with A or times out.

## 1-WebSocketClose

RFC 6455's closing handshake: either endpoint may close first, or both at once. The two local types are dual.

# Exercise 2

Each of the first four is broken by one change: predict what fails, and for whom, before you press Check, then fix it. Complete the last two.

## 2-a

`Timeout` without `a3()`: question 5 of Exercise 1.

## 2-b

`Timeout` without `TOc()`.

## 2-c

`b()` from q to r on both sides of the mixed choice.

## 2-d

Not a mixed choice at all: an ordinary choice.

## 2-CancellableRPC

CT fails: on the left, Server never hears back. Add the messages that tell Server, and Auditor, which side was taken.

## 2-StreamCancel

Valid, but stops after one chunk. Make it recursive so Server streams until Client cancels, then try the two predictions in its comments.

# Exercise 4

Knowledge of choice, for those who finish early: seven changes to `Calculator`, each explained in its comments. Srv observes; Alice has to end up knowing how it went. Predict the flags before you read the comments.

## 4-AliceActsUntold

On the right, Alice sends `note()` to Carol.

## 4-AliceSpeaksFirst

On the right, Alice pokes Srv, the observer, and Srv acks.

## 4-AliceOnlySends

On the left, Alice only sends `note()` to Carol.

## 4-NoOneTellsAlice

`Calculator` without `cancel()` to Alice.

## 4-AliceNeverHearsBack

`Calculator` without the results to Alice.

## 4-AliceCannotTell

Both branches send Alice `done()`; only one expects her `ack()`.

## 4-SameLabelBothSides

Alice receives `cancel()` on both sides of the mixed choice.

# From the slides

The protocols on the workshop's slides, in slide order. On the slides a mixed choice is written `} or {`; here it names its observer, as the checker needs: `} or A -> B {`. Slides 20 and 27 to 29 use `1-Timeout`.

## Bank

- title: Bank
- from: Slides 4, 12

C asks for a statement or makes a payment, in a loop. Valid: each branch starts with its own message to S.

## BankTimeout

- title: Bank with a timeout
- from: Slides 9 to 11

S may overrule C with `timeout()`. **CT** fails: after `pData()`, C never hears from S. Add `ok() from S to C;` after it.

## ThreeRoles

- title: Three roles
- from: Slides 14 to 16

`A → B:a1 . A → C:a2 . B → C:a3`: check it, then compare its local types with the slides.

## TwoRoleTimeout

- title: Timeout, two roles
- from: Slides 17, 18

`A → B:a1 . G1 + B → A:a2 . G2`, with G1 and G2 empty. **CT** fails: A never learns which side was taken. Start G1 with a message from B to A.

## BuyOrQuote

- title: Buy or quote
- from: Slide 21

A directed choice in Scribble: A buys or asks for a quote, and B tells C. Valid.

## ClearTermination

- title: Clear termination
- from: Slides 21, 24

Timeout without `a3()`, as `2-a`. **CT** fails: on the left, C never hears from B.

## WellFormedness

- title: Well-formedness
- from: Slide 22

`b()` from A to C on both sides: committing on the right, not on the left. **WF** fails.

## Balance

- title: Balance
- from: Slide 23

C takes part only when A buys. **BA** fails.

## SingleDecision

- title: Single decision
- from: Slides 25, 26

Which side is good? On the right, C sends `TOd()` to D before hearing from B. **SD** fails. Fix: `TOc() from A to C;` after `TOa()`.

# Examples

From `examples/scribble` in scribble-gt-scala, each with a change to try.

## Calculator

- from: Table 1
- what: Carol asks Srv for a sum or a difference; the answer goes to Carol, then Alice. Exercise 3 runs its Erlang.
- mixed choice: Srv is the observer: it may overrule Carol's `sum()` or `diff()` with `timeout()`. Carol tells Alice with `cancel()`.
- look: Carol's EFSM: after `sum()` she must still take `timeout()`.
- try: Delete `cancel() from Carol to Alice;`
- result: **SD** and **BA** fail: nobody tells Alice.

## Interrupt

- from: In the paper
- what: P sends `Start()`, then streams `More()`, or sends `Stop()` and waits for `Ack()`.
- mixed choice: Q is the observer: it may overrule P with `Interrupt()`. A `More()` already in flight is stale, and purged.
- look: Q's EFSM, then P's: `Interrupt()` can reach P in every left-hand state.
- try: Delete `Ack() from Q to P;`
- result: **CT** fails: after `Stop()`, P never hears from Q.

## FailureHandling

- from: In the paper
- what: Master M, worker W, failure detector FD. Each round: `HB()` to FD, `OK()` to M, `result()`, `more()`.
- mixed choice: FD is the observer: missing a heartbeat, it overrules W with `Timeout()` and tells M with `Crash()`. `@failed` marks the right side as W's failure.
- look: W's EFSM: `Timeout()` can arrive anywhere in its round.
- try: Delete `Crash() from FD to M;`
- result: **SD** and **BA** fail: M waits for a `result()` that never comes.

## TwoBuyer

- from: Table 1
- what: Alice asks Seller for a title, both buyers get a quote, Alice tells Bob her share, Bob accepts or rejects.
- mixed choice: Two nested mixed choices, both with Seller as observer: it may overrule with `not_available()` in the outer one and `response_timeout()` in the inner one. Both buyers are told.
- look: Seller's EFSM: an entry state per mixed choice.
- try: Delete `not_available() from Seller to Bob;`
- result: **SD** and **BA** fail: Bob waits for a quote.

## CircuitBreaker

- from: Table 1
- what: Usr calls an API, which asks a Controller: `service_operational()`, `error_notice()` or `shutdown_api()`. Storage follows.
- mixed choice: The API is the observer: it may overrule the Controller with `timeout()`; the Controller tells Storage, the API tells Usr, and the loop repeats.
- look: The comments: three acks for clear termination, `cancel_ack()` for projection onto Storage.
- try: Delete `shutdown_ack() from API to Controller;`
- result: **CT** fails: the Controller finishes without hearing from the API.

## amqp_selective_consumer

- title: AMQP selective consumer
- from: RabbitMQ case study
- what: From RabbitMQ's Erlang client: Consumer subscribes via Channel; Server delivers until someone cancels. The completed generated Erlang passes RabbitMQ's tests.
- mixed choice: Any of the three may cancel: Server by an ordinary choice, Channel as observer of the outer mixed choice (while Server decides), Consumer as observer of the inner one (while a delivery is in flight).
- look: Channel's EFSM: both mixed choices inside the loop.
- try: Delete `process_message() from Channel to Consumer;`
- result: Checks pass; projection onto Consumer fails: nothing tells it the Server's choice.

## SMTP

- from: Table 1
- what: A mail session: `Ehlo()`, optional `StartTls()`, `Auth()`, then `Mail()`, `Rcpt()`, `Data()` repeatedly, `Quit()`. 31 states per role.
- mixed choice: S is the observer of two mixed choices, each a timeout: before the first command, and before `Data()`.
- look: The EFSM tab, and the `…Commit` labels, marked `!!!`.
- try: Rename `QuitCommit` to `Quit`.
- result: **WF** fails: `Quit` does two jobs. The `Commit` labels keep them apart.

# More examples

Also in `examples/scribble`.

## DiffTwoBuyer

TwoBuyer, but when the title is unavailable Seller tells Alice, and Alice tells Bob.

## DistributedLogging

- from: Table 1

A controller and a logger in a loop; the controller may time the logger out and restart it.

## Fibonacci

- from: Table 1

Fibonacci numbers back and forth until `a` stops; `b` may answer `error()`. Delete the commented `ack()`: **CT** fails.

## OnlineWallet

- from: Table 1

Log in, then pay or quit, in a loop. The server may time the client out, and tells the authenticator.

## SimpleVoting

Authenticate, then vote `Yes()` or `No()`; the server may answer `error()`. The mixed choice is in one branch of an ordinary choice.

## Timeout

- from: Figure 1

Figure 1 as in the artifact; `1-Timeout` is the same, with comments.

## TravelAgency

- from: Table 1

Accept, reject or resubmit a quote, in a loop. The agency may adjust the price; then the client cancels with both.
