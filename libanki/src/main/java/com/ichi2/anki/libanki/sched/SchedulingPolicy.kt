// SPDX-License-Identifier: GPL-3.0-or-later

/*
 * Authoritative-path extension point for external scheduling policies.
 *
 * The v3 scheduler already treats the client-chosen `newState` inside
 * [CardAnswer] as the authoritative scheduling decision (this is exactly the
 * mechanism Anki's desktop custom-scheduling hook uses). This interface lets a
 * policy participate in that decision without bypassing the backend: every
 * adjusted [CardAnswer] still goes through `backend.answerCard`, so the change
 * is validated, persisted, journalled (undo) and synced exactly like a native
 * scheduling decision.
 *
 * CONTRACT (all implementations must uphold):
 *  - Identity when disabled: a disabled policy returns its inputs unchanged
 *    and must not record anything.
 *  - Never throw: the scheduler guards every call; a policy failure degrades
 *    to stock behaviour.
 *  - Never drop work: [selectQueueCandidate] only reorders candidates that the
 *    backend already queued; no card is removed, skipped or mutated by
 *    reordering. Learning (intraday) cards are time-critical and must keep
 *    backend order.
 */
package com.ichi2.anki.libanki.sched

import anki.scheduler.CardAnswer
import anki.scheduler.QueuedCards
import com.ichi2.anki.libanki.Collection

interface SchedulingPolicyHook {
    /** Number of queue candidates the scheduler should fetch (>= 1). */
    val queueFetchLimit: Int
        get() = 1

    /**
     * Called once per graded answer, after the client picked `newState` and
     * before the backend applies it. Return the (possibly adjusted) answer;
     * return [answer] unchanged to decline.
     */
    fun beforeAnswerCard(
        col: Collection,
        answer: CardAnswer,
    ): CardAnswer = answer

    /**
     * Given the fetched queue candidates, the index of the entry to surface
     * first. Must be within `0 until candidates.cardsCount`; 0 keeps backend
     * order. Implementations must not mutate [candidates].
     */
    fun selectQueueCandidate(
        col: Collection,
        candidates: QueuedCards,
    ): Int = 0
}

/** Registry holding the process-wide policy, if any. Null = stock behaviour. */
object SchedulingPolicyRegistry {
    @Volatile
    var hook: SchedulingPolicyHook? = null
}
