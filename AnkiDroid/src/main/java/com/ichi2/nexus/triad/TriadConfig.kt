/*
 * Copyright (c) 2026 WasewaseX
 *
 * NEXUS-TRIAD v1.9 configuration. Pure data — the Android adapter
 * (NexusTriadSettings) loads it from SharedPreferences.
 *
 * MIGRATION SAFETY: `enabled = false` (the default) makes every TRIAD entry
 * point a strict identity — stock AnkiDroid behaviour, no recording, no
 * reordering, no interval changes. Each capability is independently
 * toggleable so its effect can be validated in isolation.
 */
package com.ichi2.nexus.triad

data class TriadConfig(
    /** Master switch. false = fully inert (no recording, no scheduling effect). */
    val enabled: Boolean = false,
    /**
     * TRIAD owns review interval decisions through the backend answer path (alpha-gated).
     *
     * EVIDENCE POLICY (v1.9.1): ships DISABLED. The only real-data gate to date (v1.7,
     * Duolingo HLR public release, user-level held-out splits) showed NO held-out logloss
     * gain beyond FSRS-5, so interval adjustments stay off by default and on-device gate
     * acceptance does NOT re-enable them. Enabling this capability is an explicit operator
     * decision that must follow the activation protocol in docs/nexus/TRIAD_SCHEDULER.md.
     */
    val intervalOwnershipEnabled: Boolean = false,
    /** TRIAD owns review prioritization (review queue only; learning keeps backend order). */
    val prioritizationEnabled: Boolean = true,
    /** Session pacing recommendations are computed (surfaced read-only; never enforced). */
    val pacingRecommendationsEnabled: Boolean = true,
    /** Hard bounds on any TRIAD interval divergence from the FSRS proposal. */
    val minIntervalRatio: Double = 0.6,
    val maxIntervalRatio: Double = 1.4,
    /** Max interval (days) TRIAD may ever produce. */
    val maxIntervalDays: Int = 365,
    /** Queue candidates fetched when prioritization is on (>= 1). */
    val queueCandidates: Int = 6,
    /** Session idle timeout: a longer gap starts a new session. */
    val sessionIdleTimeoutMs: Long = 30L * 60_000L,
    /** Session budget (minutes) used by the fatigue proxy normalisation. */
    val sessionBudgetMin: Double = 20.0,
    /** Persisted observational state cap (gate rows kept in memory + on disk). */
    val maxGateRows: Int = 5000,
) {
    companion object {
        val DEFAULT = TriadConfig()
    }
}

/** Sanity-clamped view used by the policy (protects against bad prefs). */
fun TriadConfig.sanitized(): TriadConfig =
    copy(
        minIntervalRatio = TriadMath.clip(minIntervalRatio, 0.1, 1.0),
        maxIntervalRatio = TriadMath.clip(maxIntervalRatio, 1.0, 3.0),
        queueCandidates = TriadMath.clip(queueCandidates.toDouble(), 1.0, 20.0).toInt(),
        maxIntervalDays = TriadMath.clip(maxIntervalDays.toDouble(), 1.0, 3650.0).toInt(),
        sessionBudgetMin = TriadMath.clip(sessionBudgetMin, 1.0, 240.0),
        maxGateRows = TriadMath.clip(maxGateRows.toDouble(), 100.0, 100_000.0).toInt(),
    )
