/*
 * Copyright (c) 2026 WasewaseX
 *
 * NEXUS-TRIAD v1.8 engine — Kotlin port of the validated v1.8 simulation
 * engine (benchmarks/v18_nexus_triad_benchmark, WasewaseX/NEXUS-AnkiDroid).
 *
 * Four components (per the NEXUS-TRIAD spec):
 *  1. Cold-start intelligence: shared empirical-Bayes Beta(1,1) priors per
 *     skill; shrunk toward the prior while evidence is scarce.
 *  2. FSRS memory engine (authoritative, EXTERNAL): this engine NEVER
 *     modifies FSRS state; it only READS an optional P_FSRS estimate.
 *  3. Contextual recall predictor: observables only (recent accuracy,
 *     latency slowdown, workload, session-scope fatigue, absence gap).
 *      delta = w_acc*(recent_acc-0.85) - w_lat*max(0,slowdown-1)
 *              - w_wl*max(0, due/30-0.5) - w_fat*fatigueProxy
 *              - w_abs*min(gapDays/7, 1),  clipped to +/- 1.5 logits.
 *  4. NEXUS controller: session-scope state exposed to the host app.
 *
 * Hybrid prediction (user's formula, verbatim):
 *      P_hybrid = sigmoid(logit(P_eff) + alpha * delta_context)
 * with alpha = 0 until the AlphaGate demonstrates reliable out-of-sample
 * value (held-out logloss improvement >= 0.005 with >= 40 logged events).
 *
 * HARD CONSTRAINTS carried from the spec:
 *  - fatigue is SESSION-SCOPE ONLY: never persisted, never touches FSRS
 *    stability, recomputed fresh each session;
 *  - no silent due-review skipping (controller must never drop due work);
 *  - alpha = 0 until validated out-of-sample (conservative gate).
 *
 * Pure Kotlin: no Android, no serialization, no coroutines dependencies.
 */
package com.ichi2.nexus.triad

import kotlin.math.exp
import kotlin.math.ln
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

object TriadMath {
    fun clip(x: Double, lo: Double, hi: Double): Double = max(lo, min(hi, x))

    fun logit(p: Double): Double {
        val q = clip(p, 1e-4, 1.0 - 1e-4)
        return ln(q / (1.0 - q))
    }

    fun sigmoid(x: Double): Double =
        if (x >= 0.0) 1.0 / (1.0 + exp(-x)) else {
            val z = exp(x); z / (1.0 + z)
        }

    /** The user's formula: P_hybrid = sigmoid(logit(P_eff) + alpha*delta). */
    fun hybrid(pEff: Double, delta: Double, alpha: Double): Double =
        if (alpha == 0.0) clip(pEff, 1e-4, 1.0 - 1e-4)
        else sigmoid(logit(pEff) + alpha * delta)

    fun brier(preds: List<Pair<Double, Int>>): Double =
        preds.sumOf { (p, y) -> (p - y) * (p - y) } / preds.size

    fun logLoss(preds: List<Pair<Double, Int>>): Double {
        val eps = 1e-6
        return preds.sumOf { (p, y) ->
            -(y * ln(max(p, eps)) + (1 - y) * ln(max(1.0 - p, eps)))
        } / preds.size
    }
}

/**
 * Component 1 — cold-start intelligence.
 * Per-skill Beta(1,1) posteriors over observed outcomes; shrunk_level blends
 * an observed EMA with the prior mean via n/(n+k) shrinkage.
 */
class SkillPriors(private val kSkill: Double = 8.0, private val kCard: Double = 3.0) {
    private val alpha = HashMap<String, Double>()
    private val beta = HashMap<String, Double>()

    init {
        // (per-skill map is lazy; missing == Beta(1,1) -> mean 0.5)
    }

    private fun a(skill: String) = alpha.getOrPut(skill) { 1.0 }
    private fun b(skill: String) = beta.getOrPut(skill) { 1.0 }

    fun update(skill: String, correct: Boolean) {
        if (correct) alpha[skill] = a(skill) + 1.0 else beta[skill] = b(skill) + 1.0
    }

    fun mean(skill: String): Double = a(skill) / (a(skill) + b(skill))

    fun shrunkLevel(skill: String, observedLevel: Double, n: Int): Double =
        (n * observedLevel + kSkill * mean(skill)) / (n + kSkill)

    /** Cold-start prediction shrinkage for cards with < kCard reviews. */
    fun shrunkP(skill: String, pSched: Double, cardReviews: Int): Double {
        if (cardReviews >= kCard.toInt()) return TriadMath.clip(pSched, 1e-4, 1.0 - 1e-4)
        return (cardReviews * pSched + kCard * mean(skill)) / (kCard + cardReviews)
    }
}

/**
 * Component 3 — contextual recall predictor over OBSERVABLES only.
 * Session-scope quantities reset in startSession() and never persist.
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

    // session scope (reset each session)
    var sessionEvents = 0; private set
    var sessionElapsedMin = 0.0; private set
    private var sessionBudgetMin = 1.0
    private var dueCount = 0

    // absence history
    var lastDay: Long? = null; private set
    var gapDays = 0L; private set

    fun startSession(day: Long, dueCountIn: Int, budgetMin: Double) {
        sessionEvents = 0
        sessionElapsedMin = 0.0
        sessionBudgetMin = max(1e-6, budgetMin)
        dueCount = dueCountIn
        lastDay?.let { gapDays = max(0L, day - it) }
    }

    fun endSession(day: Long) { lastDay = day }

    fun observeEvent(kind: String, minutes: Double, skill: String, correct: Boolean) {
        val x = if (correct) 1.0 else 0.0
        accEma = (1.0 - accEmaRate) * accEma + accEmaRate * x
        val m = max(1e-6, minutes)
        val prev = latEma[kind]
        if (prev == null) {
            latEma[kind] = m; latRecent[kind] = m
        } else {
            latEma[kind] = (1.0 - latEmaRate) * prev + latEmaRate * m
            val r = latRecent[kind] ?: m
            latRecent[kind] = 0.5 * r + 0.5 * m
        }
        sessionEvents += 1
        // skill evidence tracked by SkillPriors via update(); nothing else here
    }

    fun observeCard(cardId: Long) { cardReviews[cardId] = (cardReviews[cardId] ?: 0) + 1 }
    fun reviews(cardId: Long): Int = cardReviews[cardId] ?: 0

    fun addElapsed(minutes: Double) { sessionElapsedMin += max(0.0, minutes) }

    /** SESSION SCOPE ONLY. */
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
 * Deterministic systematic 30% validation split (seq % 10 < 3).
 */
class AlphaGate(
    private val grid: List<Double> = listOf(0.0, 0.25, 0.5, 0.75, 1.0),
    private val valFraction: Double = 0.3,
    private val minEvents: Int = 40,
    private val minImprovement: Double = 0.005,
) {
    var alpha = 0.0; private set
    var fitted = false; private set
    var fitSummary: Map<String, Double> = emptyMap(); private set
    private val rows = ArrayList<Quadruple<Int, Double, Double, Int>>()
    private var seq = 0

    data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    /** Log one DUE-review prediction (made BEFORE the outcome was observed). */
    fun log(day: Int, pEff: Double, delta: Double, correct: Boolean) {
        rows.add(Quadruple(day, pEff, delta, if (correct) 1 else 0))
        seq += 1
    }

    /** Attempt the one-time out-of-sample fit; returns true if fitted now. */
    fun maybeFit(day: Int, gateDay: Int = 14): Boolean {
        if (fitted || day < gateDay) return false
        fitted = true
        if (rows.size < minEvents) {
            fitSummary = mapOf("n" to rows.size.toDouble(), "alpha" to 0.0)
            return false
        }
        val nVal = max(1, Math.round(valFraction * 10).toInt())
        val validation = rows.filterIndexed { i, _ -> i % 10 < nVal }
        val train = rows.filterIndexed { i, _ -> i % 10 >= nVal }
        if (train.isEmpty() || validation.isEmpty()) {
            fitSummary = mapOf("n" to rows.size.toDouble(), "alpha" to 0.0)
            return false
        }
        fun ll(rs: List<Quadruple<Int, Double, Double, Int>>, a: Double): Double =
            TriadMath.logLoss(rs.map { TriadMath.hybrid(it.second, it.third, a) to it.fourth })

        val baseVal = ll(validation, 0.0)
        var bestAlpha = 0.0
        var bestVal = baseVal
        for (a in grid) {
            val v = ll(validation, a)
            if (v < bestVal - 1e-12) { bestAlpha = a; bestVal = v }
        }
        val improvement = baseVal - bestVal
        alpha = if (improvement >= minImprovement && bestAlpha > 0.0) bestAlpha else 0.0
        fitSummary = mapOf(
            "n" to rows.size.toDouble(),
            "alpha" to alpha,
            "improvement" to improvement,
        )
        return true
    }

    fun allPredictions(): List<Triple<Double, Double, Int>> = rows.map { Triple(it.second, it.third, it.fourth) }
    fun loggedCount(): Int = rows.size
}

/**
 * NEXUS-TRIAD v1.8 facade. FSRS remains the authoritative memory engine:
 * this class never mutates scheduler state. Feed graded answers via
 * [onAnswer]; read P_hybrid via the returned records or [predict].
 */
class TriadEngine(
    priors: SkillPriors = SkillPriors(),
    gate: AlphaGate = AlphaGate(),
    gateDayEvents: Int = 300,          // in-app gate trigger (event-count based)
) {
    val priors = priors
    val gate = gate
    val context = ContextState()
    private var gateTriggered = false
    private var sessionOpen = false
    private var sessionStartDay = 0L

    /** day = epoch-day (System.currentTimeMillis / 86_400_000). */
    fun startSession(day: Long, dueCount: Int, budgetMin: Double) {
        context.startSession(day, dueCount, budgetMin)
        sessionOpen = true
        sessionStartDay = day
    }

    fun endSession(day: Long) {
        context.endSession(day)
        sessionOpen = false
    }

    /**
     * Predict BEFORE the answer. pFsrs: FSRS retrievability if the host can
     * supply it (component 2 input); when null, the engine's own per-skill
     * prior mean is used (declared fallback, flagged in the prediction).
     */
    fun predict(cardId: Long, skill: String, pFsrs: Double?, dueCount: Int = 0): TriadPrediction {
        if (!sessionOpen) startSession(sessionStartDay, dueCount, 20.0)
        val cardN = context.reviews(cardId)
        val base = pFsrs ?: priors.mean(skill)
        val pEff = priors.shrunkP(skill, base, cardN)
        val delta = context.delta()
        val accepted = gate.fitted && gate.alpha > 0.0
        return TriadPrediction(
            pEff = pEff,
            delta = delta,
            alpha = gate.alpha,
            pHybrid = TriadMath.hybrid(pEff, delta, gate.alpha),
            alphaAccepted = accepted,
        )
    }

    /**
     * Record one graded answer (called AFTER the backend scheduled it).
     * correct = rating != AGAIN. kind/latency: observable proxies.
     */
    fun onAnswer(
        cardId: Long,
        skill: String,
        ratingAgain: Boolean,
        pFsrs: Double? = null,
        latencyMin: Double = 0.0,
        day: Long = 0L,
        dueCount: Int = 0,
    ): TriadAnswerRecord {
        if (!sessionOpen) startSession(if (day != 0L) day else 0L, dueCount, 20.0)
        // predict-then-observe (the prediction was logged with the outcome)
        val cardN = context.reviews(cardId)
        val base = pFsrs ?: priors.mean(skill)
        val pEff = priors.shrunkP(skill, base, cardN)
        val delta = context.delta()
        val correct = !ratingAgain
        gate.log((day % Int.MAX_VALUE).toInt(), pEff, delta, correct)
        // observe
        context.observeCard(cardId)
        context.observeEvent("review", latencyMin, skill, correct)
        context.addElapsed(latencyMin)
        priors.update(skill, correct)
        // one-time out-of-sample gate
        if (!gateTriggered && gate.loggedCount() >= gateDayEvents) {
            gate.maybeFit(Int.MAX_VALUE, Int.MAX_VALUE - 1) // fit NOW (day arg bypass)
            gateTriggered = true
        }
        return TriadAnswerRecord(
            prediction = TriadPrediction(
                pEff, delta, gate.alpha,
                TriadMath.hybrid(pEff, delta, gate.alpha),
                gate.fitted && gate.alpha > 0.0,
            ),
            correct = correct,
            sessionEvents = context.sessionEvents,
        )
    }

    /** Controller snapshot (component 4 inputs for the host app). */
    fun controllerSnapshot(): Map<String, Double> = mapOf(
        "recent_acc" to context.recentAcc(),
        "fatigue_proxy" to context.fatigueProxy(),
        "slowdown" to context.slowdown(),
        "delta" to context.delta(),
        "alpha" to gate.alpha,
        "session_events" to context.sessionEvents.toDouble(),
    )
}
