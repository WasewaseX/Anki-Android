/*
 * Copyright (c) 2026 WasewaseX
 *
 * NEXUS-TRIAD v1.9 — scheduling-layer math.
 *
 * Pure Kotlin: no Android, no serialization, no coroutines dependencies.
 * All functions are total (clamped inputs) so a broken estimate can never
 * produce a NaN/Infinite interval.
 *
 * FSRS power curve (FSRS-5/6 family):
 *     R(t, S) = (1 + F * t / S) ^ decay,   F = 0.9^(1/decay) - 1
 * The backend exposes `decay` per card (ComputeMemoryStateResponse.decay);
 * with the default decay of -0.5 this reduces to F = 19/81.
 */
package com.ichi2.nexus.triad

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object TriadMath {
    fun clip(
        x: Double,
        lo: Double,
        hi: Double,
    ): Double = max(lo, min(hi, x))

    fun logit(p: Double): Double {
        val q = clip(p, 1e-4, 1.0 - 1e-4)
        return ln(q / (1.0 - q))
    }

    fun sigmoid(x: Double): Double =
        if (x >= 0.0) {
            1.0 / (1.0 + exp(-x))
        } else {
            val z = exp(x)
            z / (1.0 + z)
        }

    /** The user's formula: P_hybrid = sigmoid(logit(P_eff) + alpha*delta). */
    fun hybrid(
        pEff: Double,
        delta: Double,
        alpha: Double,
    ): Double =
        if (alpha == 0.0) {
            clip(pEff, 1e-4, 1.0 - 1e-4)
        } else {
            sigmoid(logit(pEff) + alpha * delta)
        }

    fun brier(preds: List<Pair<Double, Int>>): Double = preds.sumOf { (p, y) -> (p - y) * (p - y) } / preds.size

    fun logLoss(preds: List<Pair<Double, Int>>): Double {
        val eps = 1e-6
        return preds.sumOf { (p, y) ->
            -(y * ln(max(p, eps)) + (1 - y) * ln(max(1.0 - p, eps)))
        } / preds.size
    }

    /**
     * FSRS retrievability after [elapsedDays] for a card with [stability].
     * [decay] comes from the backend (ComputeMemoryStateResponse.decay);
     * falls back to the FSRS default of -0.5 when unset/unusable.
     */
    fun retrievability(
        elapsedDays: Double,
        stability: Double,
        decay: Double,
    ): Double {
        val d = if (decay < -1e-6 || decay > 1e-6) decay else -0.5
        val s = max(0.1, stability)
        val f = 0.9.pow(1.0 / d) - 1.0
        val t = max(0.0, elapsedDays)
        return (1.0 + f * t / s).pow(d)
    }

    /**
     * Interval (days) at which the FSRS power curve hits [target]:
     * inverse of [retrievability]. t = S * (target^(1/decay) - 1) / F.
     */
    fun intervalForTarget(
        target: Double,
        stability: Double,
        decay: Double,
    ): Double {
        val d = if (decay < -1e-6 || decay > 1e-6) decay else -0.5
        val s = max(0.1, stability)
        val f = 0.9.pow(1.0 / d) - 1.0
        val p = clip(target, 1e-4, 0.9999)
        return s * (p.pow(1.0 / d) - 1.0) / f
    }
}
