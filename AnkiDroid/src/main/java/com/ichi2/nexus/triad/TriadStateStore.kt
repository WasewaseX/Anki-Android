/*
 * Copyright (c) 2026 WasewaseX
 *
 * NEXUS-TRIAD v1.9 observational-state persistence.
 *
 * Persisted (per deck engine): Beta prior counts, alpha-gate rows + fit
 * result, last-activity day (absence history). This is OBSERVATIONAL/policy
 * state only — it is never synced, never touches the collection, and losing
 * it is harmless (the engine re-learns from fresh evidence).
 *
 * File: <filesDir>/nexus_triad/triad_state_v1.json
 *  - versioned ("version": 1) for future migrations;
 *  - atomic write (tmp file + rename);
 *  - corrupt/missing file => fresh state (reviewing must never break).
 *
 * Pure state layer: file I/O is injected so JVM tests can use temp dirs.
 */
package com.ichi2.nexus.triad

import java.io.File

data class TriadGateRow(
    val day: Long,
    val pEff: Double,
    val delta: Double,
    val correct: Int,
)

data class TriadEnginePersistentState(
    val priors: Map<String, Pair<Double, Double>> = emptyMap(),
    val gateRows: List<TriadGateRow> = emptyList(),
    val gateFitted: Boolean = false,
    val gateAlpha: Double = 0.0,
    val lastDay: Long? = null,
)

data class TriadPersistentState(
    val version: Int = 1,
    val engines: Map<Long, TriadEnginePersistentState> = emptyMap(),
)

interface TriadStateBackend {
    fun read(): String?

    fun write(json: String)
}

class TriadFileStateBackend(private val file: File) : TriadStateBackend {
    override fun read(): String? =
        try {
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            null
        }

    override fun write(json: String) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                // rename can fail across filesystems; fall back to direct write
                file.writeText(json)
                tmp.delete()
            }
        } catch (e: Exception) {
            // persistence is best-effort; reviewing must never break
        }
    }
}

object TriadStateCodec {
    fun encode(state: TriadPersistentState): String {
        val root = org.json.JSONObject()
        root.put("version", state.version)
        val engines = org.json.JSONObject()
        for ((deck, es) in state.engines) {
            val obj = org.json.JSONObject()
            val priors = org.json.JSONObject()
            for ((skill, ab) in es.priors) {
                priors.put(skill, org.json.JSONObject().put("a", ab.first).put("b", ab.second))
            }
            obj.put("priors", priors)
            val rows = org.json.JSONArray()
            for (r in es.gateRows) {
                rows.put(org.json.JSONArray().put(r.day).put(r.pEff).put(r.delta).put(r.correct))
            }
            obj.put("gate_rows", rows)
            obj.put("gate_fitted", es.gateFitted)
            obj.put("gate_alpha", es.gateAlpha)
            es.lastDay?.let { obj.put("last_day", it) }
            engines.put(deck.toString(), obj)
        }
        root.put("engines", engines)
        return root.toString()
    }

    fun decode(json: String): TriadPersistentState? {
        return try {
            val root = org.json.JSONObject(json)
            val version = root.optInt("version", 1)
            if (version != 1) return null
            val engines = LinkedHashMap<Long, TriadEnginePersistentState>()
            val enginesJson = root.optJSONObject("engines") ?: org.json.JSONObject()
            for (deckKey in enginesJson.keys()) {
                val deck = deckKey.toLongOrNull() ?: continue
                val obj = enginesJson.getJSONObject(deckKey)
                val priors = LinkedHashMap<String, Pair<Double, Double>>()
                val priorsJson = obj.optJSONObject("priors") ?: org.json.JSONObject()
                for (skill in priorsJson.keys()) {
                    val ab = priorsJson.getJSONObject(skill)
                    priors[skill] = ab.optDouble("a", 1.0) to ab.optDouble("b", 1.0)
                }
                val rows = ArrayList<TriadGateRow>()
                val rowsJson = obj.optJSONArray("gate_rows") ?: org.json.JSONArray()
                for (i in 0 until rowsJson.length()) {
                    val r = rowsJson.getJSONArray(i)
                    rows.add(
                        TriadGateRow(
                            day = r.optLong(0),
                            pEff = r.optDouble(1),
                            delta = r.optDouble(2),
                            correct = r.optInt(3),
                        ),
                    )
                }
                engines[deck] =
                    TriadEnginePersistentState(
                        priors = priors,
                        gateRows = rows,
                        gateFitted = obj.optBoolean("gate_fitted", false),
                        gateAlpha = obj.optDouble("gate_alpha", 0.0),
                        lastDay = if (obj.has("last_day")) obj.optLong("last_day") else null,
                    )
            }
            TriadPersistentState(version = version, engines = engines)
        } catch (e: Exception) {
            null
        }
    }
}

class TriadStateStore(
    private val backend: TriadStateBackend,
    private val maxRows: Int = 5000,
) {
    private var state: TriadPersistentState = load()

    private fun load(): TriadPersistentState {
        val raw = backend.read() ?: return TriadPersistentState()
        val decoded = TriadStateCodec.decode(raw) ?: return TriadPersistentState()
        return TriadPersistentState(
            version = decoded.version,
            engines =
                decoded.engines.mapValues { (_, es) ->
                    es.copy(gateRows = es.gateRows.takeLast(maxRows))
                },
        )
    }

    @Synchronized
    fun current(): TriadPersistentState = state

    @Synchronized
    fun engineState(deckId: Long): TriadEnginePersistentState = state.engines[deckId] ?: TriadEnginePersistentState()

    @Synchronized
    fun updateEngine(deckId: Long, transform: (TriadEnginePersistentState) -> TriadEnginePersistentState) {
        val updated = transform(state.engines[deckId] ?: TriadEnginePersistentState())
        val bounded = updated.copy(gateRows = updated.gateRows.takeLast(maxRows))
        state = state.copy(engines = state.engines + (deckId to bounded))
        backend.write(TriadStateCodec.encode(state))
    }
}
