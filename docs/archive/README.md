# Historical port planning

These files were written **before** the Windows desktop host existed. They are
kept because they contain the device measurements, the rejected options and the
phase estimates that shaped the port; they are not documentation of the current
code.

- [port-assessment.md](port-assessment.md) — measured feasibility and device data
- [port-design.md](port-design.md) — the original layering/event/phasing plan

The current implementation, its safety invariants and its build and verification
commands are described from [../01-overview.md](../01-overview.md) onwards. (The
Windows desktop host these documents were written alongside was removed on
2026-11-20; its code survives only in git history.) The product decisions that
the port must not violate are in
[../08-port-philosophy.md](../08-port-philosophy.md).
