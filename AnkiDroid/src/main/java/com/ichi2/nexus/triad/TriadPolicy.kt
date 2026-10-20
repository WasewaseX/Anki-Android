/*
 * Copyright (c) 2026 WasewaseX
 *
 * NEXUS-TRIAD v1.9 scheduling policy — the layer that makes TRIAD a genuine
 * scheduler through AnkiDroid's authoritative backend scheduling path.
 *
 * OWNERSHIP MAP (what TRIAD now decides, and how):
 *
 *  1. Review prioritization  [selectQueueCandidate]
 *     The v3 queue candidates are fetched with queueFetchLimit > 1; TRIAD
 *     surfaces the most at-risk due review card first (lowest cold-start-
 *     shrunk predicted recall). INVARIANTS: candidates are only REORDERED
 *     (a permutation — no card is dropped or skipped); intraday LEARNING
 *     cards always keep backend order (time-critical); NEW cards keep
 *     backend order in v1.
 *
 *  2. Interval decisions     [beforeAnswerCard -> maybeAdjustInterval]
 *     TRIAD decides the next graduated-review interval by inverting the
 *     FSRS power curve at the context-shifted target
 *         P_target = sigmoid(logit(desiredRetention) - alpha * delta)
 *     and clamping the result to [minRatio, maxRatio] x FSRS interval.
 *     The adjusted state is submitted through backend.answerCard — the same
 *     authoritative call stock AnkiDroid uses — so persistence, undo and
 *     sync semantics are untouched.
 *     IDENTITY GUARANTEE: alpha = 0 (until the gate validates) makes the
 *     decision exactly the FSRS proposal — short-circuited explicitly.
 *
 *  3. Contextual adaptation  [TriadEngine.context.delta()]
 *     Recent accuracy, per-card answer latency (revlog timeTaken — defect 2
 *     fix: not the inter-answer gap), REAL due counts (defect 2 fix: not a
 *     hardcoded zero), session-scope fatigue, absence gap.
 *
 *  4. Cold-start skill priors [SkillPriors.shrunkP]
 *     Cards with < 3 reviews have their predictions shrunk toward the
 *     shared per-deck prior. Priors drive TRIAD's predictions (and hence
 *     prioritization + gate evidence); intervals stay FSRS-identical until
 *     the alpha gate validates (conservative spec constraint).
 *
 *  5. Session control        [TriadSessions + TriadEngine.context]
 *     Session boundaries on idle timeout / day rollover only (defect 3
 *     fix); counters accumulate across the session; pacing output is a
 *     RECOMMENDATION — never enforced, never ends a session.
 *
 * FAILURE ISOLATION: every entry point catches Exception and degrades to
 * stock behaviour (identity). TRIAD must never break reviewing.
 */
package com.ichi2.nexus.triad

import anki.scheduler.CardAnswer
import anki.scheduler.QueuedCards
import anki.scheduler.SchedulingState
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/** Narrow collection facade the policy needs (fakes testable in JVM tests). */
interface TriadCardSource {
    data class FsrsParams(
        val decay: Double,
        val desiredRetention: Double,
    )

    data class Counts3(
        val newCount: Int,
        val lrnCount: Int,
        val revCount: Int,
    ) {
        fun total(): Int = newCount + lrnCount + revCount
    }

    fun deckId(cardId: Long): Long

    /** Number of times the card was studied (cold-start shrinkage input). */
    fun reps(cardId: Long): Int

    /** Backend FSRS params for the card's deck config; null => defaults. */
    fun fsrsParams(cardId: Long): FsrsParams?

    /** Real due counts (new/lrn/review) for the deck being studied; null => unknown. */
    fun totalCounts(): Counts3?
}

/** Default FSRS decay when the backend has no stored params. */
const val TRIAD_DEFAULT_DECAY: Double = -0.5

/** Default desired retention when the backend has no stored params. */
const val TRIAD_DEFAULT_DESIRED_RETENTION: Double = 0.9

/**
 * Core policy. Android-free: [TriadCardSource] abstracts the collection
 * (passed per call — the hook receives the live collection), [clock]
 * abstracts time, [TriadStateStore] abstracts persistence.
 */
open class TriadPolicy(
    private val configProvider: () -> TriadConfig,
    private val store: TriadStateStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val engines = ConcurrentHashMap<Long, TriadEngine>()
    private val sessions = ConcurrentHashMap<Long, TriadSessions>()
    private val restored = ConcurrentHashMap.newKeySet<Long>()
    private var decayCache: Double? = null
    private var lastCounts: TriadCardSource.Counts3? = null
    private var eventsSinceSave = 0

    // ------------------------------------------------------------------
    // Queue prioritization
    // ------------------------------------------------------------------

    val queueFetchLimit: Int
        get() {
            val config = runCatching { configProvider().sanitized() }.getOrNull() ?: TriadConfig.DEFAULT
            return if (config.enabled && config.prioritizationEnabled) config.queueCandidates else 1
        }

    /**
     * Return the index of the candidate to surface first.
     * CONTRACT: permutation only (no drops); learning/new keep order.
     */
    fun selectQueueCandidate(
        candidates: QueuedCards,
        cardSource: TriadCardSource,
    ): Int {
        val config = runCatching { configProvider().sanitized() }.getOrNull() ?: return 0
        if (!config.enabled || !config.prioritizationEnabled) return 0
        return try {
            val entries = candidates.cardsList
            if (entries.isEmpty()) return 0
            lastCounts =
                TriadCardSource.Counts3(candidates.newCount, candidates.learningCount, candidates.reviewCount)
            val top = entries[0]
            // Time-critical + new queues keep backend order (invariant)
            if (top.queue != QueuedCards.Queue.REVIEW) return 0
            val decay = cachedDecay(cardSource)
            var bestIdx = 0
            var bestScore = Double.MAX_VALUE
            for ((i, entry) in entries.withIndex()) {
                if (entry.queue != top.queue) continue
                val score = priorityScore(entry, decay) ?: continue
                if (score < bestScore) {
                    bestScore = score
                    bestIdx = i
                }
            }
            bestIdx
        } catch (e: Exception) {
            0
        }
    }

    /** Lower score = surface first; null = not scoreable (keeps order). */
    private fun priorityScore(
        entry: QueuedCards.QueuedCard,
        decay: Double,
    ): Double? {
        if (entry.states.current.kindCase != SchedulingState.KindCase.NORMAL) return null
        val normal = entry.states.current.normal
        if (normal.kindCase != SchedulingState.Normal.KindCase.REVIEW) return null
        val review = normal.review
        if (!review.hasMemoryState()) return null
        val cardId = entry.card.id
        val deckId = entry.card.deckId
        val stability = review.memoryState.stability.toDouble()
        val pFsrs = TriadMath.retrievability(review.elapsedDays.toDouble(), stability, decay)
        val engine = engineFor(deckId)
        val skill = "deck:$deckId"
        return engine.predict(cardId, skill, pFsrs, cardReps = entry.card.reps).pEff
    }

    // ------------------------------------------------------------------
    // Answer path: record + interval decision
    // ------------------------------------------------------------------

    /**
     * Called once per graded answer before the backend applies it.
     * Returns the (possibly adjusted) answer; identity on decline/failure.
     */
    fun beforeAnswerCard(
        cardSource: TriadCardSource,
        answer: CardAnswer,
    ): CardAnswer {
        val config = runCatching { configProvider().sanitized() }.getOrNull() ?: return answer
        if (!config.enabled) return answer
        return try {
            val now = clock()
            val day = now / DAY_MS
            val cardId = answer.cardId
            val deckId = cardSource.deckId(cardId)
            val engine = engineFor(deckId)
            val skill = "deck:$deckId"

            // Real workload (defect 2 fix)
            val counts = lastCounts ?: cardSource.totalCounts()
            val dueCount = counts?.total() ?: 0

            // Session boundary (defect 3 fix): accumulate; reset only on
            // idle timeout / rollover.
            val sessionTracker = sessions.getOrPut(deckId) { TriadSessions(config) }
            if (sessionTracker.shouldStartNewSession(now, day)) {
                // seed absence gap from persisted last day, then start fresh
                val lastDay = store.engineState(deckId).lastDay
                if (lastDay != null && lastDay < day) {
                    engine.context.endSession(lastDay)
                }
                engine.context.startSession(day, dueCount, config.sessionBudgetMin)
            }
            sessionTracker.markActivity(now, day)

            // FSRS inputs (component 2 read-only)
            val params = cardSource.fsrsParams(cardId)
            val decay = params?.decay ?: cachedDecay(cardSource)
            val desiredRetention = params?.desiredRetention ?: TRIAD_DEFAULT_DESIRED_RETENTION
            val pFsrs = currentRetrievability(answer.currentState, decay)
            val reps = cardSource.reps(cardId)

            // Record the graded answer (defect 1 fix: prediction is stored,
            // fed to the gate, and drives prioritization/interval decisions)
            val latencyMin = max(0, answer.millisecondsTaken).toDouble() / 60_000.0
            engine.onAnswer(
                cardId = cardId,
                skill = skill,
                ratingAgain = answer.rating == CardAnswer.Rating.AGAIN,
                pFsrs = pFsrs,
                latencyMin = latencyMin,
                day = day,
                dueCount = dueCount,
                cardReps = reps,
            )

            val adjusted = maybeAdjustInterval(answer, engine, config, decay, desiredRetention)

            eventsSinceSave += 1
            if (eventsSinceSave >= SAVE_EVERY_N_EVENTS) {
                eventsSinceSave = 0
                persistEngine(deckId, engine, day)
            }
            adjusted
        } catch (e: Exception) {
            // TRIAD must never break reviewing
            answer
        }
    }

    /** Pacing recommendation (read-only, never enforced). */
    fun pacingRecommendation(deckId: Long): TriadSessions.PacingRecommendation {
        val config = runCatching { configProvider().sanitized() }.getOrNull() ?: return TriadSessions.PacingRecommendation.NONE
        val engine = engines[deckId] ?: return TriadSessions.PacingRecommendation.NONE
        return sessions.getOrPut(deckId) { TriadSessions(config) }.pacingRecommendation(engine.context.fatigueProxy())
    }

    /** Diagnostics surface (read-only). */
    fun snapshot(deckId: Long): Map<String, Double> = engines[deckId]?.controllerSnapshot() ?: emptyMap()

    fun engineCount(): Int = engines.size

    fun engineFor(deckId: Long): TriadEngine {
        val engine = engines.getOrPut(deckId) { TriadEngine() }
        restoreIfNeeded(deckId, engine)
        return engine
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun maybeAdjustInterval(
        answer: CardAnswer,
        engine: TriadEngine,
        config: TriadConfig,
        decay: Double,
        desiredRetention: Double,
    ): CardAnswer {
        val newState = answer.newState
        // Only graduated REVIEW states are adjusted. AGAIN-triggered
        // relearning steps, intraday learning steps, new cards and filtered
        // states are never touched (hard invariant).
        if (newState.kindCase != SchedulingState.KindCase.NORMAL) return answer
        val normal = newState.normal
        if (normal.kindCase != SchedulingState.Normal.KindCase.REVIEW) return answer
        val review = normal.review
        if (!review.hasMemoryState()) return answer

        val decision =
            engine.decideReviewInterval(
                alpha = engine.gate.alpha,
                delta = engine.context.delta(),
                desiredRetention = desiredRetention,
                fsrsScheduledDays = review.scheduledDays,
                stability = review.memoryState.stability.toDouble(),
                decay = decay,
                config = config,
            )
        if (!decision.applied) return answer

        val newReview = review.toBuilder().setScheduledDays(decision.intervalDays).build()
        val newNormal = normal.toBuilder().setReview(newReview).build()
        val newNewState = newState.toBuilder().setNormal(newNormal).build()
        return answer.toBuilder().setNewState(newNewState).build()
    }

    /** FSRS retrievability of the card being answered (component 2 input). */
    private fun currentRetrievability(
        current: SchedulingState,
        decay: Double,
    ): Double? {
        if (current.kindCase != SchedulingState.KindCase.NORMAL) return null
        val normal = current.normal
        if (normal.kindCase != SchedulingState.Normal.KindCase.REVIEW) return null
        val review = normal.review
        if (!review.hasMemoryState()) return null
        val stability = review.memoryState.stability.toDouble()
        if (stability <= 0.0) return null
        return TriadMath.retrievability(review.elapsedDays.toDouble(), stability, decay)
    }

    private fun cachedDecay(cardSource: TriadCardSource): Double {
        decayCache?.let { return it }
        val d =
            runCatching { cardSource.fsrsParams(0L) }.getOrNull()?.decay
                ?: TRIAD_DEFAULT_DECAY
        decayCache = if (d == 0.0) TRIAD_DEFAULT_DECAY else d
        return decayCache!!
    }

    private fun restoreIfNeeded(
        deckId: Long,
        engine: TriadEngine,
    ) {
        if (!restored.add(deckId)) return
        try {
            val persisted = store.engineState(deckId)
            if (persisted.priors.isNotEmpty()) {
                engine.priors.restore(persisted.priors)
            }
            if (persisted.gateRows.isNotEmpty() || persisted.gateFitted) {
                engine.gate.restore(
                    restoredRows = persisted.gateRows.map { AlphaGate.Row(it.day, it.pEff, it.delta, it.correct) },
                    restoredAlpha = persisted.gateAlpha,
                    restoredFitted = persisted.gateFitted,
                )
            }
        } catch (e: Exception) {
            // fresh state on any restore failure
        }
    }

    private fun persistEngine(
        deckId: Long,
        engine: TriadEngine,
        day: Long,
    ) {
        try {
            store.updateEngine(deckId) { current ->
                TriadEnginePersistentState(
                    priors = engine.priors.snapshot(),
                    gateRows =
                        engine.gate.rowsSnapshot().map {
                            TriadGateRow(it.day, it.pEff, it.delta, it.correct)
                        },
                    gateFitted = engine.gate.fitted,
                    gateAlpha = engine.gate.alpha,
                    lastDay = max(current.lastDay ?: 0L, day).takeIf { it > 0 },
                )
            }
        } catch (e: Exception) {
            // best-effort
        }
    }

    companion object {
        const val DAY_MS: Long = 86_400_000L
        const val SAVE_EVERY_N_EVENTS: Int = 25

        /** Testing seam: min/final interval mapping shared by tests. */
        fun clampInterval(
            tTargetDays: Double,
            fsrsDays: Int,
            config: TriadConfig,
        ): Int =
            min(
                TriadMath.clip(tTargetDays, fsrsDays * config.minIntervalRatio, fsrsDays * config.maxIntervalRatio),
                config.maxIntervalDays.toDouble(),
            ).toInt().coerceAtLeast(1)
    }
}
