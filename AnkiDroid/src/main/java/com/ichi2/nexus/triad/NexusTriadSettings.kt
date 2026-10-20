/*
 * Copyright (c) 2026 WasewaseX
 *
 * NEXUS-TRIAD v1.9 Android adapter: SharedPreferences-backed config loader.
 *
 * All flags default OFF (migration safety): a fresh or upgraded install
 * behaves exactly like stock AnkiDroid until the user enables NEXUS-TRIAD.
 */
package com.ichi2.nexus.triad

import android.content.Context
import android.content.SharedPreferences

object NexusTriadSettings {
    const val PREFS_NAME: String = "nexus_triad"

    private const val KEY_ENABLED = "triad_enabled"
    private const val KEY_INTERVALS = "triad_interval_ownership"
    private const val KEY_PRIORITIZATION = "triad_prioritization"
    private const val KEY_PACING = "triad_pacing_recommendations"
    private const val KEY_MIN_RATIO = "triad_min_interval_ratio"
    private const val KEY_MAX_RATIO = "triad_max_interval_ratio"
    private const val KEY_QUEUE_CANDIDATES = "triad_queue_candidates"
    private const val KEY_IDLE_TIMEOUT_MS = "triad_session_idle_timeout_ms"
    private const val KEY_SESSION_BUDGET_MIN = "triad_session_budget_min"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(prefs: SharedPreferences): TriadConfig =
        TriadConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            intervalOwnershipEnabled = prefs.getBoolean(KEY_INTERVALS, true),
            prioritizationEnabled = prefs.getBoolean(KEY_PRIORITIZATION, true),
            pacingRecommendationsEnabled = prefs.getBoolean(KEY_PACING, true),
            minIntervalRatio = prefs.getFloat(KEY_MIN_RATIO, 0.6f).toDouble(),
            maxIntervalRatio = prefs.getFloat(KEY_MAX_RATIO, 1.4f).toDouble(),
            queueCandidates = prefs.getInt(KEY_QUEUE_CANDIDATES, 6),
            sessionIdleTimeoutMs = prefs.getLong(KEY_IDLE_TIMEOUT_MS, 30L * 60_000L),
            sessionBudgetMin = prefs.getFloat(KEY_SESSION_BUDGET_MIN, 20f).toDouble(),
        )

    fun load(context: Context): TriadConfig = load(prefs(context))
}
