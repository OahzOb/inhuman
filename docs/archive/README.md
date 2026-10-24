# Historical port planning

These files were written **before** the Windows desktop host existed. They are
kept because they contain the device measurements, the rejected options and the
phase estimates that shaped the port; they are not documentation of the current
code.

- [port-assessment.md](port-assessment.md) — measured feasibility and device data
- [port-design.md](port-design.md) — the original layering/event/phasing plan

The current Windows implementation, its security invariants and its build and
verification commands are in [../12-desktop.md](../12-desktop.md). The product
decisions that the port must not violate are in
[../08-port-philosophy.md](../08-port-philosophy.md).
