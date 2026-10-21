# NEXUS-TRIAD v1.9 — Scheduling Ownership on the Authoritative Backend Path

## What changed vs v1.8

The v1.8 integration recorded answers *after* the backend had already scheduled
them (`TriadTracker.onAnswer` called post-`answerCardInner`, return value
discarded). An independent code review correctly identified this as
**"experimental AnkiDroid with an extra tracking layer — not a scheduling
upgrade"**. v1.9 replaces it with a genuine scheduling policy that acts *inside*
AnkiDroid's authoritative backend scheduling path.

## The authoritative path and where TRIAD sits

AnkiDroid 2.26 uses the v3 scheduler. Every graded answer funnels through
`libanki`'s `Scheduler`:

```
Reviewer / ReviewerViewModel / legacy viewer / ContentProvider
        └─> Scheduler.answerCard(...)            (3 overloads + ext overload)
              └─> buildAnswer(card, states, rating)   — client picks newState
              └─> backend.answerCard(CardAnswer)      — THE authoritative write
```

The client-chosen `newState` inside `CardAnswer` **is** the scheduling decision
(the exact mechanism Anki's desktop custom-scheduling hook uses). v1.9 adds a
guarded extension point, `SchedulingPolicyHook`
(`libanki/.../sched/SchedulingPolicy.kt`), invoked:

1. in all three `Scheduler.answerCard` overloads, via `applySchedulingPolicy()`
   — every answer passes through the hook exactly once;
2. in the queue getter, which may fetch >1 candidates and reorder them
   (permutation only).

`SchedulingPolicyRegistry.hook` is registered once in `AnkiDroidApp.onCreate`.
When the master flag is off (the default), every method is a strict identity and
nothing is recorded. All hook calls are failure-isolated: a policy exception
degrades to stock behavior.

## What TRIAD owns (capability by capability)

| Capability | Mechanism | Invariants |
|---|---|---|
| Review prioritization | `selectQueueCandidate`: among fetched queue candidates, surface the due-review card with the lowest cold-start-shrunk predicted recall first | Candidates are only **reordered** (permutation — nothing dropped or skipped); intraday LEARNING cards always keep backend order; NEW cards keep backend order in v1 |
| Interval decisions | `decideReviewInterval`: invert the FSRS power curve at the context-shifted target `P_target = sigmoid(logit(desiredRetention) − α·Δ)`; write the result into `newState.normal.review.scheduledDays` and submit through `backend.answerCard` | Only graduated REVIEW states; learning/relearning/new/filtered never touched; hard clamps `[0.6×, 1.4×]` of the FSRS proposal and `≤ 365 d`; persistence/undo/sync semantics untouched |
| Contextual adaptation | `ContextState.delta()`: recent accuracy, **per-card** answer latency (revlog `timeTaken`), **real** due counts (from the queue fetch / `sched.counts()`), session-scope fatigue, absence gap | Fatigue is session-scope only — never persisted, never touches FSRS stability |
| Cold-start skill priors | `SkillPriors`: per-deck Beta(1,1) posteriors; `shrunkP` blends FSRS retrievability with the prior for cards with < 3 reviews | Priors drive predictions (→ prioritization + gate evidence); intervals stay FSRS-identical until α validates |
| Session control | `TriadSessions`: boundaries on idle timeout (30 min) / day rollover only; pacing recommendation | **Recommendation-only** — never caps, ends, or skips a session |

## The gated hybrid (user's formula, verbatim)

```
P_hybrid = σ(logit(P_FSRS) + α·Δ_context)      α = 0 until validated
```

v1.9 applies the same formula along the time axis for intervals: the hybrid
recall curve `σ(logit(R_fsrs(t)) + α·Δ)` is set equal to the deck's desired
retention and solved for `t`.

**Identity guarantee:** α = 0 makes `P_hybrid = P_FSRS` and
`P_target = desiredRetention`, which is exactly what the backend inverted to
produce the FSRS interval. The code short-circuits α = 0 to the FSRS interval
explicitly — so even with the feature flag ON, intervals are byte-identical to
stock FSRS until the gate validates. This makes the flag and the gate two
independent layers of conservatism.

## Alpha gate (defect 4 fix)

`AlphaGate.maybeFit()` uses two **disjoint** deterministic splits of the logged
due-review predictions (made before outcomes were observed):

- **TRAIN** (`i % 10 ≥ 3`): selects α from the grid {0, 0.25, 0.5, 0.75, 1.0}
  by logloss improvement.
- **VALIDATION** (`i % 10 < 3`): held out from selection; α* is accepted only
  if it also improves validation logloss by ≥ 0.005.

Fit requires ≥ 300 events across ≥ 14 distinct days (one-shot). The gate state
(priors, rows, α) persists in `<filesDir>/nexus_triad/triad_state_v1.json`
(versioned, atomic write, corrupt-safe, never synced — observational state
only).

## Defects fixed from the independent v1.8 code review

1. **Predictor not in the loop** → predictions are now computed inside the
   scheduling path, fed to prioritization and interval decisions; the FSRS
   retrievability input comes from `SchedulingState.Review.memoryState` +
   backend decay (`computeMemoryState`), not from a guess.
2. **Rough proxies** → latency is the per-card revlog answer time
   (`CardAnswer.millisecondsTaken`); workload is the real due count — the
   hardcoded zero and the inter-answer-gap proxy are gone.
3. **Session reset per answer** → the v1.8 tracker restarted the session when
   `sessionEvents == 1` (which happens after *every* first-increment), so
   counters never accumulated. Session boundaries now live in `TriadSessions`
   (idle timeout / rollover only); counters live in the engine and accumulate.
4. **Alpha gate not independent** → train-selection + held-out confirmation,
   enforced by regression tests that construct adversarial data where selection
   and confirmation disagree (the v1.8 gate would have accepted).

## Feature flags & migration

`SharedPreferences` file `nexus_triad`:

| Key | Default | Effect when off |
|---|---|---|
| `triad_enabled` | **false** | TRIAD fully inert (no recording, no reorder, no interval change) |
| `triad_interval_ownership` | **false** (v1.9.1 evidence policy) | interval decisions fall back to FSRS identity; on-device gate acceptance does NOT re-enable them |
| `triad_prioritization` | true | queue fetch stays at 1 candidate, backend order |
| `triad_pacing_recommendations` | true | no recommendation computed |

Default-off means a fresh or upgraded install behaves exactly like stock
AnkiDroid until the user opts in. There are **no collection schema changes**
— nothing to migrate inside the Anki database; the only new on-disk state is
the app-private, versioned JSON file above.

## Honest limits (no superiority claims)

- **No claim** is made that TRIAD outperforms FSRS for real learners. That
  requires an independent, preregistered, real-learner evaluation. Simulation
  references in `WasewaseX/NEXUS-AnkiDroid` are simulations, not real-learner
  evidence, and the simulator's behavioral model overlaps with TRIAD's
  contextual assumptions (disclosed circularity).
- With the v1.9.1 evidence policy, interval ownership ships DISABLED: even with the
  master flag on and the on-device gate accepted (α > 0), intervals are **exactly**
  FSRS until an operator explicitly enables `triad_interval_ownership` after the
  activation protocol below. The only real-data gate to date (v1.7, Duolingo HLR
  public release, user-level held-out splits) showed no held-out logloss gain beyond
  FSRS-5 — that is why the default is off.
- Once α > 0, interval divergence is bounded to ±40% of the FSRS proposal by
  construction, but the *effect* of those adjustments on retention is not yet
  measured in any controlled study.
- The `newState` memory state is not recomputed when the interval changes
  (same semantics as Anki's own "set due date"); divergence is bounded by the
  clamps.
- Prioritization only reorders the fetched candidate window (up to
  `triad_queue_candidates`, default 6); it does not reorder the deck-wide queue
  in the backend.

## Activation protocol (v1.9.1 — for interval ownership only)

1. New evidence PASS recorded in `WasewaseX/NEXUS-AnkiDroid` (`docs/SESSION_CONTROL_AUDIT.md`):
   a deterministic held-out split with ≥ 0.005 logloss improvement over FSRS-5 and ≥ 40 events,
   on public data or a preregistered simulation — labeled honestly (simulation ≠ real learner
   evidence). The v1.7 real-data gate FAILED this bar; that failure is why the default is off.
2. An explicit operator review referencing step 1, recorded in the release notes of the build
   that ships the change.
3. Only then may `triad_interval_ownership` be defaulted on (or enabled per device), with the
   on-device gate still required to validate (α > 0) before any divergence occurs.

## Regression tests

`AnkiDroid/src/test/java/com/ichi2/nexus/triad/`:

- `TriadMathTest` — retrievability/inversion round trips, hybrid identity at
  α = 0, degenerate-input clamping.
- `AlphaGateTest` — split independence (adversarial train/val disagreement
  must reject), volume/day requirements, restore no-refit.
- `TriadSessionTest` — defect 3 regression (accumulation), per-card latency and
  real-workload inputs (defect 2 regressions), absence gaps, advisory pacing.
- `TriadIntervalDecisionTest` — α = 0 exact identity, clamp bounds, non-review
  states untouched, disabled-config strict identity, prioritization invariants
  (learning keeps order, most-at-risk review first, candidate permutation).
- `TriadStateStoreTest` — round trip, corrupt recovery, version gate, row cap,
  per-deck isolation (Robolectric for org.json).
