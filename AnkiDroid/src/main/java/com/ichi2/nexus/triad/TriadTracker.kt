/*
 * Copyright (c) 2026 WasewaseX
 *
 * NEXUS-TRIAD v1.8 session tracker — the host-app-facing singleton.
 *
 * The reviewer calls [onAnswer] after each graded answer and may read
 * [predict] / [snapshot] for decision support (component 4). Everything is
 * failure-isolated by design: callers wrap tracker calls in try/catch and
 * the tracker itself never touches the Anki backend — FSRS remains the
 * authoritative memory engine.
 */
package com.ichi2.nexus.triad

import java.util.concurrent.ConcurrentHashMap

object TriadTracker {
    const val DAY_MS: Long = 86_400_000L

    private val engines = ConcurrentHashMap<Long, TriadEngine>()
    private val lastAnswerMs = ConcurrentHashMap<Long, Long>()

    /** Default deck-level "skill" bucket when no finer skill is supplied. */
    fun engineFor(deckId: Long): TriadEngine = engines.getOrPut(deckId) { TriadEngine() }

    /**
     * Record one graded answer. ratingAgain=true for AGAIN (incorrect).
     * pFsrs: optional FSRS retrievability of the card (component 2 input);
     * when null, the engine's shared-prior fallback is used.
     */
    fun onAnswer(
        deckId: Long,
        cardId: Long,
        ratingAgain: Boolean,
        pFsrs: Double? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): TriadAnswerRecord? = try {
        val engine = engineFor(deckId)
        val day = nowMs / DAY_MS
        val prev = lastAnswerMs[deckId]
        // inter-answer gap as an observable latency/fatigue proxy (minutes),
        // capped so a long absence is handled by the absence feature instead
        val latencyMin = if (prev == null) 0.0 else ((nowMs - prev).coerceAtMost(DAY_MS) / 60_000.0)
        lastAnswerMs[deckId] = nowMs
        val dueGuess = 0 // due-count wiring is a future backend integration
        val rec = engine.onAnswer(
            cardId = cardId, skill = "deck:$deckId", ratingAgain = ratingAgain,
            pFsrs = pFsrs, latencyMin = latencyMin, day = day, dueCount = dueGuess,
        )
        if (engine.context.sessionEvents == 1) engine.startSession(day, dueGuess, 20.0)
        rec
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // TRIAD must NEVER break reviewing
        null
    }

    /** Read-only P_hybrid prediction for a card (decision support). */
    fun predict(deckId: Long, cardId: Long, pFsrs: Double? = null): TriadPrediction? = try {
        engineFor(deckId).predict(cardId, "deck:$deckId", pFsrs)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        null
    }

    /** Controller snapshot for the current session of a deck. */
    fun snapshot(deckId: Long): Map<String, Double> = try {
        engineFor(deckId).controllerSnapshot()
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        emptyMap()
    }

    /** Aggregate cross-deck summary for a stats surface. */
    fun summary(): Map<String, Double> = try {
        var events = 0.0
        var alphaSum = 0.0
        var alphaDecks = 0.0
        for ((_, engine) in engines) {
            events += engine.context.sessionEvents
            val a = engine.gate.alpha
            alphaSum += a
            if (a > 0.0) alphaDecks += 1.0
        }
        mapOf(
            "decks" to engines.size.toDouble(),
            "session_events" to events,
            "mean_alpha" to (if (engines.isEmpty()) 0.0 else alphaSum / engines.size),
            "decks_with_alpha" to alphaDecks,
        )
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        emptyMap()
    }

    fun engineCount(): Int = engines.size
}
