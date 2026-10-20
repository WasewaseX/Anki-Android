/*
 * Copyright (c) 2026 WasewaseX
 *
 * Regression tests for the NEXUS-TRIAD v1.9 math layer.
 */
package com.ichi2.nexus.triad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TriadMathTest {
    private val decay = -0.5
    private val stability = 10.0

    @Test
    fun `retrievability at zero elapsed is 1`() {
        assertEquals(1.0, TriadMath.retrievability(0.0, stability, decay), 1e-9)
    }

    @Test
    fun `retrievability decreases with elapsed time`() {
        assertTrue(TriadMath.retrievability(5.0, stability, decay) > TriadMath.retrievability(15.0, stability, decay))
    }

    @Test
    fun `intervalForTarget inverts retrievability`() {
        for (t in listOf(1.0, 3.0, 10.0, 45.0)) {
            val r = TriadMath.retrievability(t, stability, decay)
            val roundTrip = TriadMath.intervalForTarget(r, stability, decay)
            assertEquals(t, roundTrip, 1e-6 * t + 1e-9)
        }
    }

    @Test
    fun `intervalForTarget at default desired retention equals stability for decay -05`() {
        // (1 + F)^(decay) = 0.9 exactly when F = 0.9^(1/decay) - 1
        val s = TriadMath.intervalForTarget(0.9, stability, decay)
        assertEquals(stability, s, 1e-6)
    }

    @Test
    fun `hybrid with alpha zero is identity on pEff`() {
        assertEquals(0.7, TriadMath.hybrid(0.7, -1.3, 0.0), 1e-9)
        assertEquals(0.7, TriadMath.hybrid(0.7, 1.3, 0.0), 1e-9)
    }

    @Test
    fun `hybrid shifts by alpha times delta in logit space`() {
        val p = 0.9
        val delta = -0.8
        val alpha = 0.5
        val expected = TriadMath.sigmoid(TriadMath.logit(p) + alpha * delta)
        assertEquals(expected, TriadMath.hybrid(p, delta, alpha), 1e-12)
        // negative delta with positive alpha must LOWER the prediction
        assertTrue(expected < p)
    }

    @Test
    fun `logit sigmoid round trip`() {
        for (p in listOf(0.01, 0.5, 0.9, 0.99)) {
            assertEquals(p, TriadMath.sigmoid(TriadMath.logit(p)), 1e-9)
        }
    }

    @Test
    fun `retrievability is clamped for degenerate stability`() {
        val r = TriadMath.retrievability(100.0, 0.0, decay)
        assertTrue(r.isFinite())
        assertTrue(r in 0.0..1.0)
    }

    @Test
    fun `degenerate decay falls back to fsrs default`() {
        val rDefault = TriadMath.retrievability(5.0, stability, -0.5)
        val rZero = TriadMath.retrievability(5.0, stability, 0.0)
        assertEquals(rDefault, rZero, 1e-12)
    }

    @Test
    fun `logLoss lower for better predictions`() {
        val good = listOf(0.9 to 1, 0.1 to 0, 0.8 to 1)
        val bad = listOf(0.2 to 1, 0.9 to 0, 0.3 to 1)
        assertTrue(TriadMath.logLoss(good) < TriadMath.logLoss(bad))
    }

    @Test
    fun `clip clamps`() {
        assertEquals(0.5, TriadMath.clip(-1.0, 0.5, 2.0), 1e-12)
        assertEquals(2.0, TriadMath.clip(3.0, 0.5, 2.0), 1e-12)
        assertEquals(1.5, TriadMath.clip(1.5, 0.5, 2.0), 1e-12)
    }
}
