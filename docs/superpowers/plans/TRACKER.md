# Feature Flag Service — Execution Tracker

Cross-session source of truth. Each task's final step updates its row and
commits it. A fresh session: read this file, dispatch the next wave whose
dependencies are all `done`.

Plan: `docs/superpowers/plans/2026-07-13-feature-flag-service.md`
Spec: `docs/superpowers/specs/2026-07-13-feature-flag-service-design.md`
Subagent model: **opus**. Waves run as parallel subagents.

| Task | Wave | Depends on | Status | Commit | Notes |
|---|---|---|---|---|---|
| 1. Maven skeleton + smoke test | 1 | — | done | 1f8b8f7 | |
| 2. API types + config model + validator | 2 | 1 | done | 020059d | |
| 3. DottedPathResolver | 2 | 1 | done | 417c25d | |
| 4. Bucketer | 2 | 1 | done | e42a575 | |
| 5. ErrorSink + rate-limited stderr | 2 | 1 | done | b6e68b2+66d41b9 | race fix after review |
| 6. RuleMatcher | 3 | 2, 3 | done | 98d4e54 | |
| 7. ConfigStore + Snapshot | 3 | 2 | done | e11288e | |
| 8. Evaluator | 4 | 4, 5, 6, 7 | done | 61057c4 | |
| 9. FeatureFlagClient | 5 | 8 | done | 8d36315 | |
| 10. Concurrency stress | 6 | 9 | pending | | |
| 11. SLT suite | 6 | 9 | pending | | |
| 12. README + final verify | 7 | 10, 11 | pending | | |

Status values: `pending` → `in_progress` → `done` (or `blocked: <reason>`).
