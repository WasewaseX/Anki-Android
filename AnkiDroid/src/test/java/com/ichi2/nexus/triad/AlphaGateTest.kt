/*
 * Copyright (c) 2026 WasewaseX
 *
 * Regression test for DEFECT 4 fix: the alpha gate must select alpha on the
 * train split and CONFIRM on a disjoint held-out validation split. These
 * tests construct datasets where selection and confirmation disagree, which
 * the v1.8 gate (single split used for both) would have accepted.
 */
package com.ichi2.nexus.triad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphaGateTest {
    /**
     * Rows where the context feature predicts the outcome in the SAME
     * direction everywhere. alpha should be selected (train) and confirmed
     * (validation).
     */
    private fun consistentRows(
        days: Int,
        perDay: Int,
    ): List<AlphaGate.Row> {
        val rows = ArrayList<AlphaGate.Row>()
        for (d in 0 until days) {
            for (i in 0 until perDay) {
                val delta = if (i % 2 == 0) 0.8 else -0.8
                val correct = i % 2 == 0 // delta predicts outcome
                rows.add(AlphaGate.Row(d.toLong(), 0.5, delta, if (correct) 1 else 0))
            }
        }
        return rows
    }

    /**
     * Rows where the context direction FLIPS between train indices
     * (i % 10 >= 3) and validation indices (i % 10 < 3): selection finds
     * signal, held-out confirmation must reject it.
     */
    private fun adversarialRows(
        days: Int,
        perDay: Int,
    ): List<AlphaGate.Row> {
        val rows = ArrayList<AlphaGate.Row>()
        var seq = 0
        for (d in 0 until days) {
            for (i in 0 until perDay) {
                val delta = if (seq % 2 == 0) 0.8 else -0.8
                val inVal = seq % 10 < 3
                val correct = if (inVal) seq % 2 != 0 else seq % 2 == 0 // flipped for validation
                rows.add(AlphaGate.Row(d.toLong(), 0.5, delta, if (correct) 1 else 0))
                seq += 1
            }
        }
        return rows
    }

    @Test
    fun `gate stays unfitted below minimum volume`() {
        val gate = AlphaGate()
        consistentRows(days = 5, perDay = 20).forEach { gate.log(it.day, it.pEff, it.delta, it.correct == 1) }
        val accepted = gate.maybeFit()
        assertFalse(accepted)
        assertEquals(0.0, gate.alpha, 0.0)
        assertTrue(gate.fitted) // one-shot attempt consumed
    }

    @Test
    fun `gate stays unfitted below minimum distinct days`() {
        val gate = AlphaGate()
        // 400 events but only 3 distinct days
        consistentRows(days = 3, perDay = 140).forEach { gate.log(it.day, it.pEff, it.delta, it.correct == 1) }
        val accepted = gate.maybeFit()
        assertFalse(accepted)
        assertEquals(0.0, gate.alpha, 0.0)
    }

    @Test
    fun `accepts alpha when train selection and held-out validation agree`() {
        val gate = AlphaGate()
        consistentRows(days = 20, perDay = 20).forEach { gate.log(it.day, it.pEff, it.delta, it.correct == 1) }
        assertTrue(gate.readyToFit())
        val accepted = gate.maybeFit()
        assertTrue(accepted)
        assertTrue(gate.alpha > 0.0)
        assertTrue(gate.fitSummary["val_improvement"]!! >= gate.fitSummary["train_improvement"]!! - 1e-9)
    }

    @Test
    fun `rejects alpha when held-out validation disagrees with train selection`() {
        val gate = AlphaGate()
        adversarialRows(days = 20, perDay = 20).forEach { gate.log(it.day, it.pEff, it.delta, it.correct == 1) }
        assertTrue(gate.readyToFit())
        val accepted = gate.maybeFit()
        // v1.8 bug: selection and acceptance on the SAME subset => this case
        // could return alpha > 0. The fixed gate requires held-out agreement.
        assertFalse("alpha must stay 0 when the held-out split disagrees", accepted)
        assertEquals(0.0, gate.alpha, 0.0)
    }

    @Test
    fun `restore preserves rows alpha and fitted state`() {
        val gate = AlphaGate()
        consistentRows(days = 20, perDay = 20).forEach { gate.log(it.day, it.pEff, it.delta, it.correct == 1) }
        gate.maybeFit()
        val restored = AlphaGate()
        restored.restore(gate.rowsSnapshot(), gate.alpha, gate.fitted)
        assertEquals(gate.loggedCount(), restored.loggedCount())
        assertEquals(gate.alpha, restored.alpha, 0.0)
        assertEquals(gate.fitted, restored.fitted)
        // a restored, already-fitted gate must not re-fit: alpha and row
        // count stay identical after the call (return reflects alpha > 0)
        val rowsBefore = restored.loggedCount()
        val alphaBefore = restored.alpha
        assertEquals(alphaBefore > 0.0, restored.maybeFit())
        assertEquals(alphaBefore, restored.alpha, 0.0)
        assertEquals(rowsBefore, restored.loggedCount())
    }

    @Test
    fun `identity holds at alpha zero for the interval decision`() {
        val engine = TriadEngine()
        val decision =
            engine.decideReviewInterval(
                alpha = 0.0,
                delta = -1.2,
                desiredRetention = 0.9,
                fsrsScheduledDays = 20,
                stability = 20.0,
                decay = -0.5,
                config = TriadConfig(enabled = true),
            )
        assertEquals(20, decision.intervalDays)
        assertEquals(false, decision.applied)
        assertEquals("alpha_zero_identity", decision.reason)
    }
}
