/*
 * Copyright (c) 2026 WasewaseX
 *
 * NEXUS-TRIAD v1.9 engine — four components per the TRIAD spec:
 *  1. Cold-start intelligence: shared empirical-Bayes Beta(1,1) priors per
 *     skill; shrunk toward the prior while evidence is scarce.
 *  2. FSRS memory engine (authoritative): the backend computes and owns FSRS
 *     state; this engine only READS retrievability/stability inputs.
 *  3. Contextual recall predictor: observables only (recent accuracy,
 *     per-card answer latency slowdown, real workload counts, session-scope
 *     fatigue, absence gap).
 *  4. NEXUS controller: session-scope state exposed to the host app.
 *
 * Fixes carried from the v1.8 code review ( WasewaseX/Anki-Android#TRIAD ):
 *  - DEFECT 1 (predictor not in the loop): predict() is now pure, and the
 *    scheduler policy consumes predictions for prioritization + interval
 *    decisions; the FSRS retrievability input is supplied by the caller.
 *  - DEFECT 2 (rough proxies): latency is per-card answer time supplied by
 *    the caller (revlog `timeTaken`), and workload is the real due count
 *    supplied by the caller — no inter-answer-gap proxy, no hardcoded zero.
 *  - DEFECT 3 (session reset per answer): session state accumulates; reset
 *    happens ONLY on an explicit startSession() (driven by idle timeout or
 *    day rollover in TriadSessions), never as a side effect of answering.
 *  - DEFECT 4 (alpha gate not independent): alpha is SELECTED on the train
 *    split and CONFIRMED on a held-out validation split the selection never
 *    touched.
 *
 * HARD CONSTRAINTS carried from the spec:
 *  - fatigue is SESSION-SCOPE ONLY: never persisted, never touches FSRS
 *    stability, recomputed fresh each session;
 *  - no silent due-review skipping;
 *  - alpha = 0 until validated out-of-sample (conservative gate);
 *  - alpha = 0 makes every decision identical to the FSRS baseline.
 *
 * Pure Kotlin: no Android, no serialization, no coroutines dependencies.
 */
package com.ichi2.nexus.triad

import kotlin.math.max
import kotlin.math.min

/** Public read-only prediction produced per card/event. */
data class TriadPrediction(
    val pEff: Double,
    val delta: Double,
    val alpha: Double,
    val pHybrid: Double,
    val alphaAccepted: Boolean,
)

/** Result of recording one graded answer. */
data class TriadAnswerRecord(
    val prediction: TriadPrediction,
    val correct: Boolean,
    val sessionEvents: Int,
)

/**
 * Component 1 — cold-start intelligence.
 * Per-skill Beta(1,1) posteriors over observed outcomes; shrunkLevel blends
 * an observed EMA with the prior mean via n/(n+k) shrinkage.
 */
class SkillPriors(
    private val kSkill: Double = 8.0,
    private val kCard: Double = 3.0,
) {
    private val alpha = HashMap<String, Double>()
    private val beta = HashMap<String, Double>()

    private fun a(skill: String) = alpha.getOrPut(skill) { 1.0 }
    private fun b(skill: String) = beta.getOrPut(skill) { 1.0 }

    fun update(
        skill: String,
        correct: Boolean,
    ) {
        if (correct) alpha[skill] = a(skill) + 1.0 else beta[skill] = b(skill) + 1.0
    }

    fun mean(skill: String): Double = a(skill) / (a(skill) + b(skill))

    fun shrunkLevel(
        skill: String,
        observedLevel: Double,
        n: Int,
    ): Double = (n * observedLevel + kSkill * mean(skill)) / (n + kSkill)

    /** Cold-start prediction shrinkage for cards with < kCard reviews. */
    fun shrunkP(
        skill: String,
        pSched: Double,
        cardReviews: Int,
    ): Double {
        if (cardReviews >= kCard.toInt()) return TriadMath.clip(pSched, 1e-4, 1.0 - 1e-4)
        return (cardReviews * pSched + kCard * mean(skill)) / (kCard + cardReviews)
    }

    /** Persisted-state accessors (JSON store). */
    fun snapshot(): Map<String, Pair<Double, Double>> =
        (alpha.keys + beta.keys).associateWith { a(it) to b(it) }

    fun restore(priors: Map<String, Pair<Double, Double>>) {
        priors.forEach { (skill, ab) ->
            alpha[skill] = ab.first
            beta[skill] = ab.second
        }
    }
}

/**
 * Component 3 — contextual recall predictor over OBSERVABLES only.
 * All inputs are supplied by the caller (defect 2 fix): per-card answer
 * latency, real due/workload counts, day stamps.
 * Session-scope quantities reset ONLY in startSession() and never persist.
 */
class ContextState(
    private val wAcc: Double = 2.0,
    private val wLat: Double = 0.8,
    private val wWorkload: Double = 0.4,
    private val wFatigue: Double = 0.5,
    private val wAbsence: Double = 0.3,
    private val accReference: Double = 0.85,
    private val workloadReference: Double = 30.0,
    private val absenceHorizonDays: Double = 7.0,
    private val deltaClip: Double = 1.5,
    private val accEmaRate: Double = 0.3,
    private val latEmaRate: Double = 0.25,
) {
    private var accEma = accReference
    private val latEma = HashMap<String, Double>()
    private val latRecent = HashMap<String, Double>()
    private val cardReviews = HashMap<Long, Int>()

    // session scope (reset each session via startSession only)
    var sessionEvents = 0
        private set
    var sessionElapsedMin = 0.0
        private set
    private var sessionBudgetMin = 20.0
    private var dueCount = 0

    // absence history (persisted scope — set by noteDay/endSession)
    var lastDay: Long? = null
        private set
    var gapDays = 0L
        private set

    fun startSession(
        day: Long,
        dueCountIn: Int,
        budgetMin: Double,
    ) {
        sessionEvents = 0
        sessionElapsedMin = 0.0
        sessionBudgetMin = max(1e-6, budgetMin)
        dueCount = dueCountIn
        lastDay?.let { gapDays = max(0L, day - it) }
    }

    fun endSession(day: Long) {
        lastDay = day
    }

    /** Real workload input (defect 2 fix) — refreshable mid-session. */
    fun updateWorkload(dueCountIn: Int) {
        dueCount = dueCountIn
    }

    fun observeEvent(
        kind: String,
        minutes: Double,
        correct: Boolean,
    ) {
        val x = if (correct) 1.0 else 0.0
        accEma = (1.0 - accEmaRate) * accEma + accEmaRate * x
        val m = max(1e-6, minutes)
        val prev = latEma[kind]
        if (prev == null) {
            latEma[kind] = m
            latRecent[kind] = m
        } else {
            latEma[kind] = (1.0 - latEmaRate) * prev + latEmaRate * m
            val r = latRecent[kind] ?: m
            latRecent[kind] = 0.5 * r + 0.5 * m
        }
        sessionEvents += 1
    }

    fun observeCard(cardId: Long) {
        cardReviews[cardId] = (cardReviews[cardId] ?: 0) + 1
    }

    fun reviews(cardId: Long): Int = cardReviews[cardId] ?: 0

    fun addElapsed(minutes: Double) {
        sessionElapsedMin += max(0.0, minutes)
    }

    /** SESSION SCOPE ONLY (hard constraint: never persisted, never touches FSRS). */
    fun fatigueProxy(): Double = sessionEvents / 40.0 + sessionElapsedMin / sessionBudgetMin

    /** Kind-controlled latency ratio (recent vs career EMA); >1 = slowing. */
    fun slowdown(): Double {
        if (latEma.isEmpty()) return 1.0
        var sum = 0.0
        for ((k, v) in latEma) sum += (latRecent[k] ?: v) / max(1e-6, v)
        return sum / latEma.size
    }

    fun recentAcc(): Double = accEma

    fun delta(): Double {
        val accTerm = wAcc * (accEma - accReference)
        val latTerm = -wLat * max(0.0, slowdown() - 1.0)
        val wlRatio = min(dueCount.toDouble(), 3.0 * workloadReference) / workloadReference
        val wlTerm = -wWorkload * max(0.0, wlRatio - 0.5)
        val fatTerm = -wFatigue * fatigueProxy()
        val absTerm = -wAbsence * min(gapDays / absenceHorizonDays, 1.0)
        return TriadMath.clip(accTerm + latTerm + wlTerm + fatTerm + absTerm, -deltaClip, deltaClip)
    }
}

/**
 * Component 3 gate — alpha = 0 until out-of-sample validation succeeds.
 *
 * DEFECT 4 FIX — two independent splits:
 *  - TRAIN (i % 10 >= 3): used to SELECT alpha* (max logloss improvement).
 *  - VALIDATION (i % 10 < 3): held out from selection; alpha* is accepted
 *    only if it also improves the VALIDATION logloss by >= minImprovement.
 * The selection set and the confirmation set are disjoint by construction.
 * Deterministic (systematic split), no randomness.
 */
class AlphaGate(
    private val grid: List<Double> = listOf(0.0, 0.25, 0.5, 0.75, 1.0),
    private val valFraction: Double = 0.3,
    private val minEvents: Int = 300,
    private val minImprovement: Double = 0.005,
    private val minDistinctDays: Int = 14,
    private val minValEvents: Int = 12,
    private val minTrainEvents: Int = 20,
) {
    var alpha = 0.0
        private set
    var fitted = false
        private set
    var fitSummary: Map<String, Double> = emptyMap()
        private set
    private val rows = ArrayList<Row>()
    private var seq = 0

    data class Row(
        val day: Long,
        val pEff: Double,
        val delta: Double,
        val correct: Int,
    )

    fun log(
        day: Long,
        pEff: Double,
        delta: Double,
        correct: Boolean,
    ) {
        rows.add(Row(day, pEff, delta, if (correct) 1 else 0))
        seq += 1
    }

    fun loggedCount(): Int = rows.size

    fun distinctDays(): Int = rows.map { it.day }.toSet().size

    fun allPredictions(): List<Triple<Double, Double, Int>> = rows.map { Triple(it.pEff, it.delta, it.correct) }

    /** Ready if volume AND time-diversity conditions hold (prevents one-binge fits). */
    fun readyToFit(): Boolean = rows.size >= minEvents && distinctDays() >= minDistinctDays

    /**
     * One-time fit: select on TRAIN, confirm on held-out VALIDATION.
     * Returns true if alpha moved above 0.
     */
    fun maybeFit(): Boolean {
        if (fitted) return alpha > 0.0
        fitted = true
        if (!readyToFit()) {
            fitSummary =
                mapOf(
                    "n" to rows.size.toDouble(),
                    "days" to distinctDays().toDouble(),
                    "alpha" to 0.0,
                )
            return false
        }
        val nVal = max(1, Math.round(valFraction * 10).toInt())
        val validation = rows.filterIndexed { i, _ -> i % 10 < nVal }
        val train = rows.filterIndexed { i, _ -> i % 10 >= nVal }
        if (train.size < minTrainEvents || validation.size < minValEvents) {
            fitSummary =
                mapOf(
                    "n" to rows.size.toDouble(),
                    "n_train" to train.size.toDouble(),
                    "n_val" to validation.size.toDouble(),
                    "alpha" to 0.0,
                )
            return false
        }

        fun ll(rs: List<Row>, a: Double): Double = TriadMath.logLoss(rs.map { TriadMath.hybrid(it.pEff, it.delta, a) to it.correct })

        // 1) SELECTION on train only
        val baseTrain = ll(train, 0.0)
        var bestAlpha = 0.0
        var bestTrain = baseTrain
        for (a in grid) {
            val v = ll(train, a)
            if (v < bestTrain - 1e-12) {
                bestAlpha = a
                bestTrain = v
            }
        }
        val trainImprovement = baseTrain - bestTrain

        // 2) CONFIRMATION on the untouched validation split
        val baseVal = ll(validation, 0.0)
        val valAtBest = ll(validation, bestAlpha)
        val valImprovement = baseVal - valAtBest

        alpha =
            if (bestAlpha > 0.0 &&
                trainImprovement >= minImprovement &&
                valImprovement >= minImprovement
            ) {
                bestAlpha
            } else {
                0.0
            }
        fitSummary =
            mapOf(
                "n" to rows.size.toDouble(),
                "n_train" to train.size.toDouble(),
                "n_val" to validation.size.toDouble(),
                "days" to distinctDays().toDouble(),
                "train_improvement" to trainImprovement,
                "val_improvement" to valImprovement,
                "alpha" to alpha,
            )
        return alpha > 0.0
    }

    /** Persisted-state accessors (JSON store). */
    fun rowsSnapshot(): List<Row> = rows.toList()

    fun restore(
        restoredRows: List<Row>,
        restoredAlpha: Double,
        restoredFitted: Boolean,
    ) {
        rows.clear()
        rows.addAll(restoredRows)
        alpha = restoredAlpha
        fitted = restoredFitted
    }
}

/**
 * NEXUS-TRIAD v1.9 facade. The backend owns FSRS state; this class owns the
 * contextual/prior layer. Session lifecycle is driven externally by
 * [TriadSessions] (defect 3 fix: no implicit session resets here).
 */
class TriadEngine(
    priors: SkillPriors = SkillPriors(),
    gate: AlphaGate = AlphaGate(),
) {
    val priors = priors
    val gate = gate
    val context = ContextState()

    /**
     * Read-only prediction (defect 1 fix: consumed by the scheduling policy).
     * Pure — never mutates session state.
     * [cardReps]: authoritative review count from the host (survives process
     * restarts); when < 0, the in-memory count is used.
     */
    fun predict(
        cardId: Long,
        skill: String,
        pFsrs: Double?,
        dueCount: Int = 0,
        cardReps: Int = -1,
    ): TriadPrediction {
        val cardN = if (cardReps >= 0) cardReps else context.reviews(cardId)
        val base = pFsrs ?: priors.mean(skill)
        val pEff = priors.shrunkP(skill, base, cardN)
        val delta = context.delta()
        return TriadPrediction(
            pEff = pEff,
            delta = delta,
            alpha = gate.alpha,
            pHybrid = TriadMath.hybrid(pEff, delta, gate.alpha),
            alphaAccepted = gate.fitted && gate.alpha > 0.0,
        )
    }

    /**
     * Record one graded answer. The caller supplies observables explicitly:
     * pFsrs (backend retrievability), latencyMin (per-card answer time),
     * day (epoch day), dueCount (real due count).
     */
    fun onAnswer(
        cardId: Long,
        skill: String,
        ratingAgain: Boolean,
        pFsrs: Double?,
        latencyMin: Double,
        day: Long,
        dueCount: Int,
        cardReps: Int = -1,
    ): TriadAnswerRecord {
        context.updateWorkload(dueCount)
        val cardN = if (cardReps >= 0) cardReps else context.reviews(cardId)
        val base = pFsrs ?: priors.mean(skill)
        val pEff = priors.shrunkP(skill, base, cardN)
        val delta = context.delta()
        val correct = !ratingAgain
        // predict-then-observe: pEff/delta depend only on pre-answer state
        gate.log(day, pEff, delta, correct)
        context.observeCard(cardId)
        context.observeEvent("review", latencyMin, correct)
        context.addElapsed(latencyMin)
        priors.update(skill, correct)
        // one-time out-of-sample gate (volume + time-diversity conditions)
        gate.maybeFit()
        return TriadAnswerRecord(
            prediction =
                TriadPrediction(
                    pEff,
                    delta,
                    gate.alpha,
                    TriadMath.hybrid(pEff, delta, gate.alpha),
                    gate.fitted && gate.alpha > 0.0,
                ),
            correct = correct,
            sessionEvents = context.sessionEvents,
        )
    }

    /**
     * Interval decision (the authoritative TRIAD decision, consumed by the
     * policy layer before the backend applies the answer).
     *
     * Rule: choose the interval whose FSRS-predicted recall equals the
     * context-shifted target
     *     P_target = sigmoid(logit(desiredRetention) - alpha * delta)
     * i.e. the hybrid curve sigma(logit(R_fsrs(t)) + alpha*delta) hits
     * desiredRetention at the chosen t (the user's formula, applied along
     * the time axis).
     *
     * IDENTITY GUARANTEE: alpha == 0 => P_target == desiredRetention, which
     * is exactly what the backend inverted to produce fsrsScheduledDays, so
     * the decision is the FSRS baseline. We short-circuit alpha==0 to the
     * FSRS interval to make this identity exact rather than float-lucky.
     */
    data class IntervalDecision(
        val intervalDays: Int,
        val ratioToFsrs: Double,
        val applied: Boolean,
        val reason: String,
    )

    fun decideReviewInterval(
        alpha: Double,
        delta: Double,
        desiredRetention: Double,
        fsrsScheduledDays: Int,
        stability: Double,
        decay: Double,
        config: TriadConfig,
    ): IntervalDecision {
        if (fsrsScheduledDays < 1) {
            return IntervalDecision(fsrsScheduledDays, 1.0, false, "fsrs_interval_lt_1d")
        }
        if (alpha == 0.0) {
            return IntervalDecision(fsrsScheduledDays, 1.0, false, "alpha_zero_identity")
        }
        if (!config.intervalOwnershipEnabled) {
            return IntervalDecision(fsrsScheduledDays, 1.0, false, "interval_ownership_disabled")
        }
        val pTarget = TriadMath.clip(
            TriadMath.sigmoid(TriadMath.logit(desiredRetention) - alpha * delta),
            0.5,
            0.99,
        )
        val tTarget = TriadMath.intervalForTarget(pTarget, stability, decay)
        val fsrsDays = fsrsScheduledDays.toDouble()
        val clamped = TriadMath.clip(
            tTarget,
            fsrsDays * config.minIntervalRatio,
            min(fsrsDays * config.maxIntervalRatio, config.maxIntervalDays.toDouble()),
        ).coerceAtLeast(1.0)
        val finalDays = clamped.toInt().coerceAtLeast(1)
        val ratio = finalDays / fsrsDays
        return IntervalDecision(
            intervalDays = finalDays,
            ratioToFsrs = ratio,
            applied = finalDays != fsrsScheduledDays,
            reason =
                if (finalDays != fsrsScheduledDays) {
                    "triad_context_adjusted"
                } else {
                    "within_rounding_of_fsrs"
                },
        )
    }

    /** Controller snapshot (component 4 inputs for the host app). */
    fun controllerSnapshot(): Map<String, Double> =
        mapOf(
            "recent_acc" to context.recentAcc(),
            "fatigue_proxy" to context.fatigueProxy(),
            "slowdown" to context.slowdown(),
            "delta" to context.delta(),
            "alpha" to gate.alpha,
            "session_events" to context.sessionEvents.toDouble(),
        )
}
