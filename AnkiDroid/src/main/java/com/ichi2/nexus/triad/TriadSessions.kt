/*
 * Copyright (c) 2026 WasewaseX
 *
 * NEXUS-TRIAD v1.9 session boundary controller.
 *
 * DEFECT 3 FIX: the v1.8 tracker restarted the session after every answer
 * (it keyed on sessionEvents == 1), so counters never accumulated. This
 * class owns ONLY the session boundary decision; the counters themselves
 * live exclusively in TriadEngine.context (single source of truth):
 *  - a session starts on the first observed answer,
 *  - it stays open across the whole session regardless of event count,
 *  - it ends ONLY on an idle timeout (config.sessionIdleTimeoutMs) or a day
 *    rollover — never as a side effect of answering.
 *
 * Session control is RECOMMENDATION-ONLY: nothing here caps, ends, or skips
 * a session (the user is always in control of stopping — NEXUS-MIX lesson).
 * Pure Kotlin; the Android policy adapter owns the clock.
 */
package com.ichi2.nexus.triad

class TriadSessions(private val config: TriadConfig) {
    private var open = false
    private var sessionDay = 0L
    private var lastActivityMs = 0L

    /**
     * Returns true iff the next observed answer must START A NEW SESSION
     * (no open session, idle timeout exceeded, or day rollover).
     */
    fun shouldStartNewSession(
        nowMs: Long,
        day: Long,
    ): Boolean =
        !open ||
            day != sessionDay ||
            (lastActivityMs > 0 && nowMs - lastActivityMs > config.sessionIdleTimeoutMs)

    /** Record activity for boundary tracking (counters live in the engine). */
    fun markActivity(
        nowMs: Long,
        day: Long,
    ) {
        open = true
        sessionDay = day
        lastActivityMs = nowMs
    }

    fun isOpen(): Boolean = open

    /** Recommendation only — never enforced anywhere. */
    enum class PacingRecommendation { NONE, CONSIDER_BREAK, WRAP_UP_WHEN_DONE }

    fun pacingRecommendation(fatigueProxy: Double): PacingRecommendation =
        when {
            !config.pacingRecommendationsEnabled || !open -> PacingRecommendation.NONE
            fatigueProxy >= 1.5 -> PacingRecommendation.CONSIDER_BREAK
            fatigueProxy >= 1.0 -> PacingRecommendation.WRAP_UP_WHEN_DONE
            else -> PacingRecommendation.NONE
        }
}
