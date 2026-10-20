/*
 * Copyright (c) 2026 WasewaseX
 *
 * NEXUS-TRIAD v1.9 Android hook: adapts [TriadPolicy] to the authoritative
 * libanki scheduling extension point (SchedulingPolicyHook). Registered once
 * in AnkiDroidApp.onCreate; every call is failure-isolated — when the master
 * flag is off, all methods are strict identities and nothing is recorded.
 *
 * Supersedes the v1.8 TriadTracker post-hoc tracker: recording now happens
 * INSIDE the scheduling path with full context (FSRS retrievability, real
 * due counts, per-card revlog latency), and TRIAD's interval decision is
 * submitted through backend.answerCard like any native scheduling decision.
 */
package com.ichi2.nexus.triad

import android.content.Context
import android.content.SharedPreferences
import anki.scheduler.CardAnswer
import anki.scheduler.QueuedCards
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.sched.SchedulingPolicyHook
import java.io.File

/** Production [TriadCardSource] over a live collection. */
class AnkiCardSource(private val col: Collection) : TriadCardSource {
    override fun deckId(cardId: Long): Long = col.getCard(cardId).did

    override fun reps(cardId: Long): Int = col.getCard(cardId).reps

    override fun fsrsParams(cardId: Long): TriadCardSource.FsrsParams? =
        try {
            val response = col.backend.computeMemoryState(cardId)
            val decay = response.decay.toDouble()
            val desired = response.desiredRetention.toDouble()
            TriadCardSource.FsrsParams(
                decay = decay.takeIf { it != 0.0 } ?: TRIAD_DEFAULT_DECAY,
                desiredRetention = desired.takeIf { it in 0.5..0.99 } ?: TRIAD_DEFAULT_DESIRED_RETENTION,
            )
        } catch (e: Exception) {
            null
        }

    override fun totalCounts(): TriadCardSource.Counts3? =
        try {
            val counts = col.sched.counts()
            TriadCardSource.Counts3(counts.new, counts.lrn, counts.rev)
        } catch (e: Exception) {
            null
        }
}

class TriadPolicyHook(context: Context) : SchedulingPolicyHook {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = NexusTriadSettings.prefs(appContext)
    private val store =
        TriadStateStore(
            TriadFileStateBackend(File(File(appContext.filesDir, "nexus_triad"), "triad_state_v1.json")),
            maxRows = NexusTriadSettings.load(prefs).maxGateRows,
        )
    private val policy =
        TriadPolicy(
            configProvider = { NexusTriadSettings.load(prefs) },
            store = store,
        )

    override val queueFetchLimit: Int
        get() = policy.queueFetchLimit

    override fun selectQueueCandidate(
        col: Collection,
        candidates: QueuedCards,
    ): Int = policy.selectQueueCandidate(candidates, AnkiCardSource(col))

    override fun beforeAnswerCard(
        col: Collection,
        answer: CardAnswer,
    ): CardAnswer = policy.beforeAnswerCard(AnkiCardSource(col), answer)

    /** Diagnostics: NEXUS-TRIAD controller snapshot for a deck (read-only). */
    fun snapshot(deckId: Long): Map<String, Double> = policy.snapshot(deckId)

    /** Diagnostics: pacing recommendation for a deck (read-only, never enforced). */
    fun pacingRecommendation(deckId: Long): TriadSessions.PacingRecommendation = policy.pacingRecommendation(deckId)
}
