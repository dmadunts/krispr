# Survivor rating rubric

Each item is one surviving mutant: a small change to the code that no test noticed. You see the target,
the file and line, the source around it (the mutated line marked `>`), and the mutation as the tool
described it. You don't know which tool produced it. Don't try to work that out.

The checkout of each target is on disk (the `checkout` field), so read the surrounding code and the tests
before you decide. Put each survivor in exactly one class:

- **a: real test gap.** Some input or scenario the code allows would behave differently with the
  mutant, and no test checks it. A developer could write a test that kills it.
- **b: equivalent or unkillable.** Nothing observable changes: the mutant behaves the same on every input
  the code's invariants allow (a boundary that assigns the same value, `> 0` on a value that is never 0,
  a branch an earlier branch already covers). It also covers changes that only affect performance or
  capacity hints.
- **c: junk.** The mutation is on code the programmer didn't write, such as a null-check intrinsic, a
  data class `equals`/`hashCode`/`copy`/`componentN`, a default-argument bitmask, a coroutine state
  machine label, a `when` exhaustiveness throw or a synthetic accessor. It also covers a description
  that can't be tied to anything on the line, so nobody could act on it.

When a description is vague (for example "negated conditional" on a line with several conditions),
work out which condition it can be. If every candidate is compiler-generated, choose c. If one is
user-written, rate that one and say which.

Output one JSON object per line, in item order:
`{"id": "S000", "class": "a", "confidence": "high|medium|low", "why": "<one sentence>"}`
