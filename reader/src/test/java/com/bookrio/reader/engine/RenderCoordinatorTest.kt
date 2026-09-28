package com.bookrio.reader.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tester RenderCoordinatoren (bitmap-typen er erstattet med String):
 *  - dedup: to samtidige kall for samme side → nøyaktig én renderer-kall
 *  - serialisering: to forskjellige sider på samme eier aldri samtidig
 *  - kansellert spesulativ overskriver aldri en nyere gjeldende side
 *  - store skjer kun under den forespurte nøkkelen
 */
class RenderCoordinatorTest {

    private class Harness {
        val renderCalls = AtomicInteger(0)
        val activeCount = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val stored = mutableListOf<Pair<String, String>>()
        private val storeMutex = Mutex()
        var slowPage: String? = null
        var slowDelayMs: Long = 0

        val coordinator = RenderCoordinator<String>(
            scope = CoroutineScope(Job() + Dispatchers.Default),
            isUsable = { it != "BAD" },
            onRendered = { owner, key, bmp ->
                synchronized(stored) { stored.add("$owner|$key" to bmp) }
            },
        )

        fun renderFn(page: Int): suspend (Int) -> String = { _ ->
            val n = renderCalls.incrementAndGet()
            val active = activeCount.incrementAndGet()
            synchronized(maxConcurrent) {
                if (active > maxConcurrent.get()) maxConcurrent.set(active)
            }
            try {
                val slow = synchronized(this) { slowPage }
                if (slow != null && page.toString() == slow) {
                    delay(slowDelayMs)
                    synchronized(this) { slowPage = null }
                }
                "side$page#$n"
            } finally {
                activeCount.decrementAndGet()
            }
        }
    }

    @Test
    fun `to samtidige kall for samme side gir nøyaktig én renderer-kall`() = runBlocking {
        val h = Harness()
        val results = listOf(
            async { h.coordinator.renderCurrent("ch0", "ch0|0|k", 0, h.renderFn(0)) },
            async { h.coordinator.renderCurrent("ch0", "ch0|0|k", 0, h.renderFn(0)) },
        ).awaitAll()

        assertEquals(1, h.renderCalls.get())
        assertEquals(listOf("side0#1", "side0#1"), results)
        h.coordinator.cancelSpeculative()
        h.coordinator.cancelAll()
    }

    @Test
    fun `forskjellige sider på samme eier serienes - aldri samtidig`() = runBlocking {
        val h = Harness()
        synchronized(h) { h.slowPage = "1"; h.slowDelayMs = 120 }
        val jobs = listOf(
            async { h.coordinator.renderCurrent("ch0", "k1", 1, h.renderFn(1)) },
            async { h.coordinator.renderCurrent("ch0", "k2", 2, h.renderFn(2)) },
            async { h.coordinator.renderCurrent("ch0", "k3", 3, h.renderFn(3)) },
        ).awaitAll()

        assertEquals(3, h.renderCalls.get())
        assertTrue("Renderere kjørte samtidig (max=${h.maxConcurrent.get()})", h.maxConcurrent.get() == 1)
        assertTrue(jobs.all { it != null })
        h.coordinator.cancelAll()
    }

    @Test
    fun `kansellert spesulativ overskriver aldri nyere gjeldende side`() = runBlocking {
        val h = Harness()
        synchronized(h) { h.slowPage = "7"; h.slowDelayMs = 400 }

        // Spesulativ prefetch av side 7 starter først (treig render).
        h.coordinator.requestSpeculative("ch0", "ch0|7|k", 7, h.renderFn(7))
        delay(50) // la spec-en ta eier-låsen

        // Gjeldende side (annet innhold) ber om render på samme eier →
        // spec-en Kanselleres og må ALDRI lagres/publiseres.
        val current = h.coordinator.renderCurrent("ch0", "ch0|9|k", 9, h.renderFn(9))

        assertTrue("gjeldende side returnerte feil innhold: $current", current!!.startsWith("side9"))
        val storedPages = h.stored.map { it.second }
        assertTrue("spec (side 7) ble publisert: $storedPages", "side7#1" !in storedPages)
        assertTrue("gjeldende side ble aldri publisert: $storedPages", storedPages.any { it.startsWith("side9") })
        h.coordinator.cancelAll()
    }

    @Test
    fun `store skjer kun under forespurt nøkkel`() = runBlocking {
        val h = Harness()
        val results = listOf(
            async { h.coordinator.renderCurrent("ch1", "ch1|4|k", 4, h.renderFn(4)) },
            async { h.coordinator.renderCurrent("ch2", "ch2|5|k", 5, h.renderFn(5)) },
        ).awaitAll()

        assertEquals(2, h.renderCalls.get())
        val storedOwners = h.stored.map { it.first }.toSet()
        assertTrue(storedOwners.contains("ch1|ch1|4|k"))
        assertTrue(storedOwners.contains("ch2|ch2|5|k"))
        assertEquals(2, h.stored.size)
        assertEquals(listOf("side4#1", "side5#2"), results.map { it })
        h.coordinator.cancelAll()
    }
}
