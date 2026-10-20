/*
 * Copyright (c) 2026 WasewaseX
 *
 * Regression tests for DEFECT 3 (session state must accumulate, not reset
 * per answer) and DEFECT 2 (per-card latency and real workload inputs).
 */
package com.ichi2.nexus.triad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriadSessionTest {
    private val config = TriadConfig(enabled = true, sessionIdleTimeoutMs = 30L * 60_000L)

    @Test
    fun `engine session counters accumulate across answers - defect 3 regression`() {
        val engine = TriadEngine()
        var now = 1_000_000L
        repeat(5) {
            engine.onAnswer(
                cardId = 1L,
                skill = "deck:1",
                ratingAgain = false,
                pFsrs = 0.9,
                latencyMin = 1.0,
                day = now / 86_400_000L,
                dueCount = 10,
            )
            now += 60_000L
        }
        assertEquals("sessionEvents must accumulate (v1.8 reset each answer)", 5, engine.context.sessionEvents)
        assertEquals(5.0, engine.context.sessionElapsedMin, 1e-9)
    }

    @Test
    fun `predict is pure and does not mutate session state`() {
        val engine = TriadEngine()
        engine.onAnswer(1L, "deck:1", false, 0.9, 1.0, 10L, 10)
        val before = engine.context.sessionEvents
        engine.predict(2L, "deck:1", 0.8)
        assertEquals(before, engine.context.sessionEvents)
    }

    @Test
    fun `session boundary starts once and survives consecutive answers`() {
        val sessions = TriadSessions(config)
        var now = 1_000_000L
        assertTrue("first answer starts a session", sessions.shouldStartNewSession(now, 10L))
        sessions.markActivity(now, 10L)
        repeat(20) {
            now += 60_000L
            assertFalse("answer ${it + 1} must NOT start a new session", sessions.shouldStartNewSession(now, 10L))
            sessions.markActivity(now, 10L)
        }
        assertTrue(sessions.isOpen())
    }

    @Test
    fun `idle timeout starts a new session`() {
        val sessions = TriadSessions(config)
        var now = 1_000_000L
        sessions.shouldStartNewSession(now, 10L)
        sessions.markActivity(now, 10L)
        now += config.sessionIdleTimeoutMs + 1
        assertTrue("idle gap must start a new session", sessions.shouldStartNewSession(now, 10L))
    }

    @Test
    fun `day rollover starts a new session`() {
        val sessions = TriadSessions(config)
        var now = 1_000_000L
        sessions.shouldStartNewSession(now, 10L)
        sessions.markActivity(now, 10L)
        now += 60_000L
        assertFalse(sessions.shouldStartNewSession(now, 10L))
        now += 60_000L
        assertTrue("day change must start a new session", sessions.shouldStartNewSession(now, 11L))
    }

    @Test
    fun `per-card latency input drives elapsed and fatigue - defect 2 regression`() {
        val engine = TriadEngine()
        // 3 answers x 2 minutes of per-card answer time (NOT inter-answer gaps)
        repeat(3) {
            engine.onAnswer(1L, "deck:1", false, 0.9, latencyMin = 2.0, day = 10L, dueCount = 10)
        }
        assertEquals(6.0, engine.context.sessionElapsedMin, 1e-9)
        val expectedFatigue = 3 / 40.0 + 6.0 / 20.0
        assertEquals(expectedFatigue, engine.context.fatigueProxy(), 1e-9)
    }

    @Test
    fun `real workload lowers delta - defect 2 regression`() {
        fun deltaFor(dueCount: Int): Double {
            val engine = TriadEngine()
            repeat(4) {
                engine.onAnswer(1L + it, "deck:1", false, 0.9, 1.0, 10L, dueCount)
            }
            return engine.context.delta()
        }
        val light = deltaFor(dueCount = 5)
        val heavy = deltaFor(dueCount = 90)
        assertTrue("heavy real workload must lower the contextual delta", heavy < light)
    }

    @Test
    fun `absence gap only counted when seeded from a previous day`() {
        val engine = TriadEngine()
        // no prior session: gap must be 0
        engine.context.startSession(10L, 10, 20.0)
        assertEquals(0L, engine.context.gapDays)
        // end day 10, start day 14 => 4-day gap
        engine.context.endSession(10L)
        engine.context.startSession(14L, 10, 20.0)
        assertEquals(4L, engine.context.gapDays)
    }

    @Test
    fun `pacing recommendation is advisory only`() {
        val sessions = TriadSessions(config)
        assertEquals(TriadSessions.PacingRecommendation.NONE, sessions.pacingRecommendation(10.0))
        // even at extreme fatigue the session stays open; recommendation != enforcement
        sessions.markActivity(1L, 1L)
        assertEquals(TriadSessions.PacingRecommendation.CONSIDER_BREAK, sessions.pacingRecommendation(10.0))
        assertTrue(sessions.isOpen())
    }
}
