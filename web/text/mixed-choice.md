<!-- The text of mixed-choice.html. Markdown, plus:
     {.lede} at the start of a paragraph gives it that class; {.reading} alone gives it to the list after it;
     <div data-include="parts/two-runs.html"></div> pulls in an HTML part;
     <div class="checks"></div> is filled with the four checks (from shared.js).
     Links to the paper are at the bottom. -->

# Mixed choice

{.lede} Mixed choice in asynchronous multiparty session types. Roles may disagree for a while on
which side of a mixed choice was taken, and can always reach a consistent state.

{.cite} Laura Bocchi, Raymond Hu, A. Laura Voinea, Simon Thompson.
[*Mixed Choice in Asynchronous Multiparty Session Types*][doi].
Proc. ACM Program. Lang. 10, OOPSLA1, article 148, 2026. [doi.org/10.1145/3798256][doi]\
Extended version, with appendices: [arXiv:2602.23927][arxiv]. Section links on this page go there.

## why mixed choice

Classical multiparty session types ([Honda, Yoshida, Carbone, POPL 2008][hyc08]) have only
**directed** choice: one role decides, and at each choice a role either only sends or only
receives. Exceptions ([Carbone, Honda, Yoshida, CONCUR 2008][chy08]), interruptible blocks
([Demangeon, Honda, Hu, Neykova, Yoshida, FMSD 2015][dhhny15]), timeouts ([Pears, Bocchi, King,
COORDINATION 2023][pbk23]; [Hou, Lagaillardie, Yoshida, ECOOP 2024][hly24]) and crash handling
([Viering, Chen, Eugster, Hu, Ziarek, ESOP 2018][vcehz18]; [Viering, Hu, Eugster, Ziarek, OOPSLA
2021][vhez21]) were each added as a separate construct ([Appendix A][a-exc]). Each involves a role
that may either send or receive at the same point. Mixed choice (MC) is one construct for all of
them. The paper's [Table 1][t1] extends existing MST examples with it.

## the construct

`Timeout`, the paper's [Figure 1][f1]:

```scribble
mixed {                  // left: the default
    a1() from A to B;
    a2() from A to C;
    a3() from B to C;
    a4() from B to A;
    a5() from C to A;
} or A -> B {            // right: B may overrule A
    TOa() from B to A;
    TOc() from B to C;
}
```

**B**, the sender on the right, is the **observer** of the MC ([§2.1][s2.1]). The left side is the
default. B may instead take the right side by sending `TOa`; the other roles learn which side was
taken from the messages they receive. Until then they may act on the left side, so for a while two
roles can be on different sides. The checks ensure that every role ends up on the side the
observer took.

The construct is asymmetric: only the observer can take the right side. Mixed choice in
synchronous session types is symmetric ([Appendix A][a-sync]).

## two runs

<div data-include="parts/two-runs.html"></div>

{.hintline} Left: **B** commits by receiving `a1`, **C** by receiving `a3`, **A** by receiving `a4`.
Right: **B** commits by sending `TOa`, **A** and **C** by receiving `TOa` and `TOc`; `a1` and `a2`
are stale, and **B** and **C** purge them on arrival.

## commitment and stale messages

The observer commits with its first action in the MC: a receive on the left, or a send on the
right. Any other role commits with its first action that causally depends on the observer's. A
committed role stays on its side.

Messages sent on the side not taken are **stale**. Each role's runtime purges them on arrival,
using only local information, so the application code never sees them ([§4.1][s4.1]).

## the four checks

<div class="checks"></div>

One error can fail two checks: fix the first **FAIL**.

## projection

The global protocol is projected onto one local type per role ([§4.2][s4.2]). In local types, `⊕`
is a select, an output-only choice; `&` is a branch, an input-only choice; and `▷` separates the
left and right sides of a mixed choice.

A protocol can pass all four checks and still fail projection with `Couldn't project to R`. This
is the usual merge failure of multiparty session types, not specific to mixed choice.

## guarantees

Communication is asynchronous, with FIFO order between each pair of roles. For a protocol that
passes the checks and projects, the paper proves **progress** ([Theorem 3.13][t3.13], [§3.3][s3.3]),
**operational correspondence** between the global type and its projections, modulo stale messages
([Theorem 4.4][t4.4]), and **orphan-message freedom** ([Theorem 4.7][t4.7], [§4.4][s4.4]).

## generated Erlang

For each role, the toolchain generates two modules ([§2.2][s2.2]). `gen_<role>.erl` is a
correct-by-construction `gen_statem` behaviour that handles commitment, switching sides and
purging; do not edit it. `<role>.erl` is a template of callbacks for the application logic. The
playground's EFSM tab shows the state machine each `gen_<role>.erl` implements, as in the paper's
[Figure 2][f2].

## further reading

**Synchronous** mixed choice: one step commits every role, so there are no stale messages.

{.reading}

- **Vasconcelos, Casal, Almeida, Mordido, ESOP 2020**: mixed sessions, binary ([Appendix A][a-vcam]).
  [doi.org/10.1007/978-3-030-44914-8_26](https://doi.org/10.1007/978-3-030-44914-8_26)
- **Castagna, Dezani-Ciancaglini, Padovani, LMCS 2012**: global types, set-theoretic trace
  semantics ([Appendix A][a-cdp]). [doi.org/10.2168/LMCS-8(1:24)2012][lmcs12]
- **Jongmans, Yoshida, ESOP 2020**: global/local bisimulation, symmetric choice ([Appendix A][a-jy]).
  [doi.org/10.1007/978-3-030-44914-8_10](https://doi.org/10.1007/978-3-030-44914-8_10)

**Asynchronous**, other approaches:

{.reading}

- **Barbanera, Dezani-Ciancaglini, PLACES 2026**: mixed choice, typing processes against global
  types by coinduction, without projection. No stale messages: every message must be received
  ([§6][s6]). [doi.org/10.4204/EPTCS.444.2](https://doi.org/10.4204/EPTCS.444.2)
- **Ciccone, Dagnino, Padovani, JLAMP 2024**: fair termination, without mixed choice; related to,
  but different from, clear termination per mixed choice ([§6][s6-ft]).
  [doi.org/10.1016/j.jlamp.2024.100964](https://doi.org/10.1016/j.jlamp.2024.100964)

{.back} [&larr; Playground](./)

[doi]: https://doi.org/10.1145/3798256
[arxiv]: https://arxiv.org/abs/2602.23927v2
[hyc08]: https://doi.org/10.1145/1328438.1328472
[chy08]: https://doi.org/10.1007/978-3-540-85361-9_32
[dhhny15]: https://doi.org/10.1007/s10703-014-0218-8
[pbk23]: https://doi.org/10.1007/978-3-031-35361-1_12
[hly24]: https://doi.org/10.4230/LIPIcs.ECOOP.2024.19
[vcehz18]: https://doi.org/10.1007/978-3-319-89884-1_28
[vhez21]: https://doi.org/10.1145/3485501
[lmcs12]: https://doi.org/10.2168/LMCS-8(1:24)2012
[a-exc]: https://arxiv.org/html/2602.23927v2#A1.SS0.SSS0.Px3
[a-sync]: https://arxiv.org/html/2602.23927v2#A1.SS0.SSS0.Px2.p1
[a-vcam]: https://arxiv.org/html/2602.23927v2#A1.SS0.SSS0.Px1.p1
[a-cdp]: https://arxiv.org/html/2602.23927v2#A1.SS0.SSS0.Px2.p2
[a-jy]: https://arxiv.org/html/2602.23927v2#A1.SS0.SSS0.Px2.p3
[t1]: https://arxiv.org/html/2602.23927v2#S5.T1
[f1]: https://arxiv.org/html/2602.23927v2#S2.F1
[f2]: https://arxiv.org/html/2602.23927v2#S2.F2
[s2.1]: https://arxiv.org/html/2602.23927v2#S2.SS1
[s2.2]: https://arxiv.org/html/2602.23927v2#S2.SS2
[s3.3]: https://arxiv.org/html/2602.23927v2#S3.SS3
[s4.1]: https://arxiv.org/html/2602.23927v2#S4.SS1
[s4.2]: https://arxiv.org/html/2602.23927v2#S4.SS2
[s4.4]: https://arxiv.org/html/2602.23927v2#S4.SS4
[s6]: https://arxiv.org/html/2602.23927v2#S6.p1
[s6-ft]: https://arxiv.org/html/2602.23927v2#S6.p8
[t3.13]: https://arxiv.org/html/2602.23927v2#S3.Thmtheorem13
[t4.4]: https://arxiv.org/html/2602.23927v2#S4.Thmtheorem4
[t4.7]: https://arxiv.org/html/2602.23927v2#S4.Thmtheorem7
