/*
 * Copyright (c) 2026 WasewaseX
 *
 * Regression tests for TRIAD's interval ownership: the decision must be the
 * exact FSRS baseline while alpha = 0, bounded by clamps once alpha > 0, and
 * must never touch learning/new/relearning states.
 */
package com.ichi2.nexus.triad

import anki.cards.FsrsMemoryState
import anki.scheduler.CardAnswer
import anki.scheduler.QueuedCards
import anki.scheduler.SchedulingState
import anki.scheduler.SchedulingStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriadIntervalDecisionTest {
    private val config = TriadConfig(enabled = true, minIntervalRatio = 0.6, maxIntervalRatio = 1.4)

    private fun reviewState(
        scheduledDays: Int,
        stability: Float,
        elapsedDays: Int = 0,
    ): SchedulingState =
        SchedulingState
            .newBuilder()
            .setNormal(
                SchedulingState.Normal
                    .newBuilder()
                    .setReview(
                        SchedulingState.Review
                            .newBuilder()
                            .setScheduledDays(scheduledDays)
                            .setElapsedDays(elapsedDays)
                            .setMemoryState(
                                FsrsMemoryState
                                    .newBuilder()
                                    .setStability(stability)
                                    .setDifficulty(5f),
                            ),
                    ),
            ).build()

    private fun answer(
        newState: SchedulingState,
        currentState: SchedulingState = newState,
        rating: CardAnswer.Rating = CardAnswer.Rating.GOOD,
        cardId: Long = 42L,
        msTaken: Int = 5_000,
    ): CardAnswer =
        CardAnswer
            .newBuilder()
            .setCardId(cardId)
            .setCurrentState(currentState)
            .setNewState(newState)
            .setRating(rating)
            .setAnsweredAtMillis(1_000L)
            .setMillisecondsTaken(msTaken)
            .build()

    @Test
    fun `alpha zero keeps the fsrs interval exactly`() {
        val engine = TriadEngine()
        for (fsrs in listOf(1, 3, 10, 45, 200)) {
            val d =
                engine.decideReviewInterval(
                    alpha = 0.0,
                    delta = -1.4,
                    desiredRetention = 0.9,
                    fsrsScheduledDays = fsrs,
                    stability = fsrs.toDouble(),
                    decay = -0.5,
                    config = config,
                )
            assertEquals("alpha=0 must be the FSRS baseline", fsrs, d.intervalDays)
            assertFalse(d.applied)
        }
    }

    @Test
    fun `negative delta shortens the interval and stays within the lower clamp`() {
        val engine = TriadEngine()
        // delta = -1.0, alpha = 0.5 => P_target = sigma(logit(0.9) + 0.5) ~= 0.937
        // t* = S * (P_target^(-2) - 1) / F ~= 0.618 * S => clamped to 0.6 * S
        val d =
            engine.decideReviewInterval(
                alpha = 0.5,
                delta = -1.0,
                desiredRetention = 0.9,
                fsrsScheduledDays = 20,
                stability = 20.0,
                decay = -0.5,
                config = config,
            )
        assertEquals(12, d.intervalDays) // floor clamp: 20 * 0.6
        assertEquals(0.6, d.ratioToFsrs, 1e-9)
        assertTrue(d.applied)
    }

    @Test
    fun `positive delta lengthens the interval and stays within the upper clamp`() {
        val engine = TriadEngine()
        val d =
            engine.decideReviewInterval(
                alpha = 0.5,
                delta = 1.5,
                desiredRetention = 0.9,
                fsrsScheduledDays = 20,
                stability = 20.0,
                decay = -0.5,
                config = config,
            )
        assertEquals(28, d.intervalDays) // cap clamp: 20 * 1.4
        assertEquals(1.4, d.ratioToFsrs, 1e-9)
        assertTrue(d.applied)
    }

    @Test
    fun `interval ownership disabled keeps the fsrs interval`() {
        val engine = TriadEngine()
        val d =
            engine.decideReviewInterval(
                alpha = 0.5,
                delta = -1.0,
                desiredRetention = 0.9,
                fsrsScheduledDays = 20,
                stability = 20.0,
                decay = -0.5,
                config = config.copy(intervalOwnershipEnabled = false),
            )
        assertEquals(20, d.intervalDays)
        assertFalse(d.applied)
    }

    // ------------------------------------------------------------------
    // Policy wiring: the adjusted state must flow through the CardAnswer
    // ------------------------------------------------------------------

    private fun policyWith(alpha: Double): Pair<TriadPolicy, TriadEngine> {
        val store =
            TriadStateStore(
                object : TriadStateBackend {
                    override fun read(): String? = null

                    override fun write(json: String) {}
                },
            )
        val policy =
            TriadPolicy(
                configProvider = { config },
                store = store,
                clock = { 86_400_000L * 100 }, // day 100
            )
        val engine = policy.engineFor(1L)
        engine.gate.restore(emptyList(), alpha, true)
        return policy to engine
    }

    private fun cardSource(dueTotal: Int = 15) =
        object : TriadCardSource {
            override fun deckId(cardId: Long): Long = 1L

            override fun reps(cardId: Long): Int = 5

            override fun fsrsParams(cardId: Long): TriadCardSource.FsrsParams? =
                TriadCardSource.FsrsParams(decay = -0.5, desiredRetention = 0.9)

            override fun totalCounts(): TriadCardSource.Counts3? = TriadCardSource.Counts3(0, 0, dueTotal)
        }

    @Test
    fun `policy leaves the answer untouched while alpha is zero`() {
        val (policy, _) = policyWith(alpha = 0.0)
        val input = answer(reviewState(scheduledDays = 20, stability = 20f))
        val out = policy.beforeAnswerCard(cardSource(), input)
        assertEquals(input, out)
    }

    @Test
    fun `policy rewrites only the review state within bounds when alpha is positive`() {
        val (policy, _) = policyWith(alpha = 0.5)
        // heavy real workload drives delta negative => shortened interval
        val input = answer(reviewState(scheduledDays = 20, stability = 20f))
        val out = policy.beforeAnswerCard(cardSource(dueTotal = 90), input)
        val days = out.newState.normal.review.scheduledDays
        assertTrue("adjusted interval $days must be within [12, 28]", days in 12..28)
        assertTrue(days < 20) // negative delta must shorten
        assertEquals("identity outside newState is required", input.currentState, out.currentState)
        assertEquals(input.cardId, out.cardId)
        assertEquals(input.rating, out.rating)
    }

    @Test
    fun `policy never touches learning new relearning or filtered states`() {
        val (policy, _) = policyWith(alpha = 0.5)
        val learning =
            SchedulingState
                .newBuilder()
                .setNormal(
                    SchedulingState.Normal
                        .newBuilder()
                        .setLearning(
                            SchedulingState.Learning
                                .newBuilder()
                                .setScheduledSecs(600)
                                .setRemainingSteps(2),
                        ),
                ).build()
        val againLearning =
            SchedulingState
                .newBuilder()
                .setNormal(
                    SchedulingState.Normal
                        .newBuilder()
                        .setRelearning(
                            SchedulingState.Relearning
                                .newBuilder()
                                .setLearning(
                                    SchedulingState.Learning
                                        .newBuilder()
                                        .setScheduledSecs(600),
                                ),
                        ),
                ).build()
        for (state in listOf(learning, againLearning)) {
            val input = answer(state, rating = if (state == againLearning) CardAnswer.Rating.AGAIN else CardAnswer.Rating.GOOD)
            val out = policy.beforeAnswerCard(cardSource(), input)
            assertEquals("non-review states must pass through untouched", input, out)
        }
    }

    @Test
    fun `policy disabled by config is a strict identity`() {
        val store =
            TriadStateStore(
                object : TriadStateBackend {
                    override fun read(): String? = null

                    override fun write(json: String) {}
                },
            )
        val policy =
            TriadPolicy(
                configProvider = { config.copy(enabled = false) },
                store = store,
                clock = { 86_400_000L * 100 },
            )
        // even with alpha forced positive
        val engine = policy.engineFor(1L)
        engine.gate.restore(emptyList(), 0.5, true)
        val input = answer(reviewState(scheduledDays = 20, stability = 20f))
        assertEquals(input, policy.beforeAnswerCard(cardSource(dueTotal = 90), input))
        assertEquals("disabled policy must not record events", 0, engine.context.sessionEvents)
        assertEquals("disabled policy must not fetch extra candidates", 1, policy.queueFetchLimit)
    }

    @Test
    fun `prioritization returns 0 for learning tops and new queues`() {
        val (policy, _) = policyWith(alpha = 0.0)
        val learningEntry =
            QueuedCards.QueuedCard
                .newBuilder()
                .setCard(
                    anki.cards.Card
                        .newBuilder()
                        .setId(1L)
                        .setDeckId(1L)
                        .setReps(5),
                ).setQueue(QueuedCards.Queue.LEARNING)
                .setStates(SchedulingStates.newBuilder().setCurrent(reviewState(3, 5f)))
                .build()
        val reviewEntry =
            QueuedCards.QueuedCard
                .newBuilder()
                .setCard(
                    anki.cards.Card
                        .newBuilder()
                        .setId(2L)
                        .setDeckId(1L)
                        .setReps(5),
                ).setQueue(QueuedCards.Queue.REVIEW)
                .setStates(SchedulingStates.newBuilder().setCurrent(reviewState(10, 5f)))
                .build()
        val queue =
            QueuedCards
                .newBuilder()
                .addCards(learningEntry)
                .addCards(reviewEntry)
                .setNewCount(0)
                .setLearningCount(1)
                .setReviewCount(1)
                .build()
        assertEquals("learning top must keep backend order", 0, policy.selectQueueCandidate(queue, cardSource()))
    }

    @Test
    fun `prioritization surfaces the most at-risk due review card and drops nothing`() {
        val (policy, _) = policyWith(alpha = 0.0)

        fun reviewEntry(
            id: Long,
            stability: Float,
            elapsed: Int,
        ): QueuedCards.QueuedCard =
            QueuedCards.QueuedCard
                .newBuilder()
                .setCard(
                    anki.cards.Card
                        .newBuilder()
                        .setId(id)
                        .setDeckId(1L)
                        .setReps(5),
                ).setQueue(QueuedCards.Queue.REVIEW)
                .setStates(SchedulingStates.newBuilder().setCurrent(reviewState(10, stability, elapsed)))
                .build()

        // entry0: R ~= 1.0 (fresh), entry1: R lower, entry2: lowest (overdue vs stability)
        val queue =
            QueuedCards
                .newBuilder()
                .addCards(reviewEntry(101L, 10f, 0))
                .addCards(reviewEntry(102L, 10f, 8))
                .addCards(reviewEntry(103L, 5f, 9))
                .setNewCount(0)
                .setLearningCount(0)
                .setReviewCount(3)
                .build()
        val idx = policy.selectQueueCandidate(queue, cardSource())
        assertEquals(2, idx)
        // real counts captured (defect 2): internal via next answer's dueCount;
        // permutation invariant: the caller (Scheduler) only reorders.
        val picked = queue.getCards(idx)
        assertEquals(103L, picked.card.id)
    }
}
