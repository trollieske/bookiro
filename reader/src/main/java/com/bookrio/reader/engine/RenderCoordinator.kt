package com.bookrio.reader.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Enkelt-eierskap for side-renderinger.
 *
 * Invarianter:
 *  - Maks ETT aktivt renderPage-kall per eier (kapittel → renderer) om gangen:
 *    alle kall mot samme eier serienes gjennom en per-eier-Mutex.
 *  - Arbeid dedupliseres på side-nøkkel: to samtidige kall for samme side deler
 *    samme Deferred og gir nøyaktig én renderer-kall.
 *  - Den synlige siden har prioritet: spesulativt arbeid på samme eier kanselleres
 *    før et gjeldende side-kall tar Mutex-en (Mutex.lock er kansellerbar).
 *  - Spesulativt arbeid er kansellerbart og starter bare når ingen gjeldende
 *    side-request er aktiv.
 *  - En ferdig bitmap publiseres via [onRendered] (cache-skriving) FØR awaitere
 *    får den, og aldri under en annen nøkkel enn den som ble bedt om.
 *
 * Generisk over bitmap-typen slik at logikken kan enhetstestes på JVM uten
 * android.graphics. eierNøkkel = kapittelindeks, sideNøkkel = cache-nøkkel.
 */
class RenderCoordinator<B : Any>(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val isUsable: (B) -> Boolean,
    private val onRendered: (ownerKey: Any, pageKey: String, bitmap: B) -> Unit,
) {
    /** Unik identifikasjon av én render-oppgave. */
    data class RenderRequest(val ownerKey: Any, val pageKey: String, val page: Int)

    private val locks = ConcurrentHashMap<Any, Mutex>()
    private val inFlight = ConcurrentHashMap<String, Deferred<B?>>()
    private val speculative = ConcurrentHashMap<String, Job>()

    private fun lockFor(ownerKey: Any): Mutex = locks.getOrPut(ownerKey) { Mutex() }

    /** true hvis ingen render pågår (spesulativ prefetch tillates da). */
    fun isIdle(): Boolean = inFlight.isEmpty()

    /** Avslutter koordinatorens interne scope (kall ved disposisjon). */
    fun cancelAll() {
        cancelSpeculative()
        scope.cancel()
    }

    /**
     * Gjeldende (synlig) side — høy prioritet. Kansellerer spesulativt arbeid på
     * samme eier, dedupliserer mot pågående arbeid og returnerer bitmapmen
     * (eller null hvis render feilet/ikke kunne valideres).
     */
    suspend fun renderCurrent(
        ownerKey: Any,
        pageKey: String,
        page: Int,
        render: suspend (Int) -> B
    ): B? {
        while (true) {
            inFlight[pageKey]?.let { d ->
                if (d.isActive) return d.await()
                inFlight.remove(pageKey, d)
            }
            cancelSpeculativeFor(ownerKey)

            val deferred = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                doRender(ownerKey, pageKey, page, render)
            }
            val existing = inFlight.putIfAbsent(pageKey, deferred)
            if (existing != null) {
                deferred.cancel()
                return existing.await()
            }
            deferred.start()
            return deferred.await()
        }
    }

    /**
     * Spesulativ prefetch: kansellerbar, lav prioritet. Gjøres BARE når ingen
     * annen render er aktiv (gjeldende side vinner alltid).
     */
    fun requestSpeculative(
        ownerKey: Any,
        pageKey: String,
        page: Int,
        render: suspend (Int) -> B,
    ) {
        if (inFlight.isNotEmpty()) return
        if (inFlight.containsKey(pageKey) || speculative.containsKey(pageKey)) return
        val job = scope.launch {
            doRender(ownerKey, pageKey, page, render)
        }
        job.invokeOnCompletion { speculative.remove(pageKey) }
        speculative[pageKey] = job
    }

    /** Eksklusiv tilgang til eiernes renderer (f.eks. prepare/klargjøring). */
    suspend fun <T> withOwner(ownerKey: Any, block: suspend () -> T): T =
        lockFor(ownerKey).withLock { block() }

    /** Kanseller alt spesulativt arbeid (kalles når siden endrer seg). */
    fun cancelSpeculative() {
        speculative.keys.toList().forEach { k ->
            speculative.remove(k)?.cancel()
        }
    }

    private fun cancelSpeculativeFor(ownerKey: Any) {
        speculative.keys.filter { it.startsWith("$ownerKey|") }.forEach { k ->
            speculative.remove(k)?.cancel()
        }
    }

    private suspend fun doRender(
        ownerKey: Any,
        pageKey: String,
        page: Int,
        render: suspend (Int) -> B,
    ): B? = lockFor(ownerKey).withLock {
        val bmp = runCatching { render(page) }.getOrNull()
        val usable = bmp?.takeIf { isUsable(it) }
        if (usable != null) {
            onRendered(ownerKey, pageKey, usable)
        }
        usable
    }
}
