# PR5 — Redstone Time Devices

- Status: Backlog
- Priority: P1
- Depends on: PR2, PR4

Implement a persistent device model and HHmm BCD output: four decimal digits, four bits per digit. Add region-safe block output, separate module config, persistence, chunk unload/load recovery.

Acceptance: exhaustive encoder tests; block access on owning region; persistence survives restart; updates never force-load chunks.
