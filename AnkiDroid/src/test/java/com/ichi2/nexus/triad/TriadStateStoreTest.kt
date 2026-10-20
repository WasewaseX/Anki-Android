/*
 * Copyright (c) 2026 WasewaseX
 *
 * Regression tests for TRIAD observational-state persistence: versioned,
 * atomic, corrupt-safe, per-engine isolated, bounded rows.
 */
package com.ichi2.nexus.triad

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class TriadStateStoreTest {
    private fun tempBackend(): Pair<TriadFileStateBackend, File> {
        val file = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "test_triad_${System.nanoTime()}.json")
        return TriadFileStateBackend(file) to file
    }

    @Test
    fun `missing file yields fresh state`() {
        val (backend, file) = tempBackend()
        val store = TriadStateStore(backend)
        assertEquals(emptyMap<Long, TriadEnginePersistentState>(), store.current().engines)
        assertNull(backend.read())
        file.delete()
    }

    @Test
    fun `engine state round trips through the file`() {
        val (backend, file) = tempBackend()
        val store = TriadStateStore(backend)
        store.updateEngine(7L) { _ ->
            TriadEnginePersistentState(
                priors = mapOf("deck:7" to (4.0 to 2.0)),
                gateRows =
                    listOf(
                        TriadGateRow(100L, 0.9, -0.4, 1),
                        TriadGateRow(101L, 0.8, 0.2, 0),
                    ),
                gateFitted = true,
                gateAlpha = 0.5,
                lastDay = 101L,
            )
        }
        assertTrue(file.exists())
        // a NEW store over the SAME backend must restore the same state
        val restored = TriadStateStore(backend).engineState(7L)
        assertEquals(mapOf("deck:7" to (4.0 to 2.0)), restored.priors)
        assertEquals(2, restored.gateRows.size)
        assertEquals(0.5, restored.gateAlpha, 0.0)
        assertTrue(restored.gateFitted)
        assertEquals(101L, restored.lastDay)
        file.delete()
    }

    @Test
    fun `corrupt file recovers to fresh state`() {
        val (backend, file) = tempBackend()
        file.parentFile?.mkdirs()
        file.writeText("{ this is not json !!!")
        val store = TriadStateStore(backend)
        assertEquals(emptyMap<Long, TriadEnginePersistentState>(), store.current().engines)
        // and a subsequent save must heal the file
        store.updateEngine(1L) { _ -> TriadEnginePersistentState(gateAlpha = 0.25) }
        assertEquals(0.25, TriadStateStore(backend).engineState(1L).gateAlpha, 0.0)
        file.delete()
    }

    @Test
    fun `wrong version is ignored`() {
        val (backend, file) = tempBackend()
        file.parentFile?.mkdirs()
        file.writeText("""{"version":99,"engines":{}}""")
        val store = TriadStateStore(backend)
        assertEquals(emptyMap<Long, TriadEnginePersistentState>(), store.current().engines)
        file.delete()
    }

    @Test
    fun `gate rows are capped to maxRows keeping the newest`() {
        val (backend, file) = tempBackend()
        val store = TriadStateStore(backend, maxRows = 10)
        store.updateEngine(3L) { current ->
            current.copy(
                gateRows = (0 until 30).map { TriadGateRow(it.toLong(), 0.9, 0.0, 1) },
            )
        }
        val rows = store.engineState(3L).gateRows
        assertEquals(10, rows.size)
        assertEquals(20L, rows.first().day)
        assertEquals(29L, rows.last().day)
        file.delete()
    }

    @Test
    fun `engines are isolated per deck`() {
        val (backend, file) = tempBackend()
        val store = TriadStateStore(backend)
        store.updateEngine(1L) { it.copy(gateAlpha = 0.5) }
        store.updateEngine(2L) { it.copy(gateAlpha = 0.0, gateFitted = true) }
        assertEquals(0.5, store.engineState(1L).gateAlpha, 0.0)
        assertTrue(store.engineState(2L).gateFitted)
        assertEquals(0.0, store.engineState(2L).gateAlpha, 0.0)
        file.delete()
    }

    @Test
    fun `codec round trip preserves doubles and nulls`() {
        val state =
            TriadPersistentState(
                engines =
                    mapOf(
                        5L to
                            TriadEnginePersistentState(
                                priors = mapOf("s1" to (1.0 to 1.0), "s2" to (9.5 to 0.5)),
                                gateRows = listOf(TriadGateRow(1L, 0.123456, -1.4999, 1)),
                                gateFitted = false,
                                gateAlpha = 0.0,
                                lastDay = null,
                            ),
                    ),
            )
        val decoded = TriadStateCodec.decode(TriadStateCodec.encode(state))
        assertEquals(state, decoded)
    }
}
