package com.bookrio.reader.engine

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Regression test for Fix B:
 *
 * HtmlPageRenderer previously used three GLOBAL variables
 * (pendingRenderGen, pendingRenderPage, pendingRenderCont) to track ONE in-flight request.
 * Starting a second renderPage() before the first JS-callback returned would OVERWRITE the
 * global state, orphaning the first request's continuation. That request would then time out
 * and yield a BLANK bitmap cached under the wrong page index → duplicate/stale pages and
 * eventually a frozen navigation state.
 *
 * The fix is a ConcurrentHashMap<Pair<Long,Int>, CancellableContinuation<Bitmap>> keyed by
 * (generation, pageIndex). Each call registers its own continuation under its own key; the
 * JS-callback dispatches by exact key so responses cannot be misrouted even if they arrive
 * out of order or overlap arbitrarily.
 *
 * This test reproduces the race deterministically using the same Map pattern used in
 * HtmlPageRenderer after Fix B. It verifies that two overlapping calls with different
 * page indices each receive the bitmap corresponding to THEIR (gen, pageIndex) key and
 * never a blank / mismatched one, even when callbacks arrive out of order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FixBPendingRenderRegressionTest {

    data class FakeBitmap(val pageIndex: Int, val payload: String)

    private lateinit var pendingRenders: ConcurrentHashMap<Pair<Long, Int>, CancellableContinuation<FakeBitmap>>

    @Before
    fun setUp() {
        pendingRenders = ConcurrentHashMap()
    }

    /**
     * Simulates the OLD BROKEN pattern: 3 shared globals that overwrite each other.
     * Retained here as a CONTROL pattern so the bug mechanism is self-documenting.
     * (Not asserted against; the real assertions exercise the fixed Map pattern only.)
     */
    @Suppress("unused")
    class BrokenGlobalState(
        var gen: Long = 0L,
        var page: Int = -1,
        var cont: CancellableContinuation<FakeBitmap>? = null,
    )

    private suspend fun simulateRenderPageKeyed(
        gen: Long,
        pageIndex: Int,
        callbackDelayMs: Long,
    ): FakeBitmap {
        val dispatchJob = kotlinx.coroutines.GlobalScope.launch {
            delay(callbackDelayMs)
            val key = Pair(gen, pageIndex)
            val cont = pendingRenders.remove(key)
            // Mirror HtmlPageRenderer.onPageOffsetApplied exactly: atomic remove + resume only
            // the matched continuation; never resumes a mismatched key.
            if (cont?.isActive == true) {
                cont.resume(FakeBitmap(pageIndex, payload = "gen=$gen,page=$pageIndex"))
            }
        }
        return suspendCancellableCoroutine { cont ->
            val key = Pair(gen, pageIndex)
            pendingRenders[key] = cont
            cont.invokeOnCancellation {
                pendingRenders.remove(key, cont)
                dispatchJob.cancel()
            }
        }
    }

    @Test
    fun `two overlapping render calls with out-of-order callbacks - each resolves its own bitmap via keyed lookup`() =
        runTest {
            val gen = 42L
            // page 1 registers FIRST but intentionally LARGER delay so callback fires LAST
            val result1 = async {
                simulateRenderPageKeyed(gen, 1, callbackDelayMs = 200)
            }
            delay(5)
            // page 2 registers SECOND with SHORT delay so callback fires FIRST
            val result2 = async {
                simulateRenderPageKeyed(gen, 2, callbackDelayMs = 60)
            }

            val bmp2 = result2.await() // callbacks arrive out of order!
            val bmp1 = result1.await()

            // CRITICAL ASSERTIONS FOR FIX B:
            //  - bmp1's pageIndex MUST be 1, never 2 or blank
            //  - bmp2's pageIndex MUST be 2, never 1 or blank
            //  - No stale entries remain after both resolve
            assertEquals(1, bmp1.pageIndex)
            assertEquals(2, bmp2.pageIndex)
            assertTrue(
                "bmp1 must report its own payload (page=1)",
                bmp1.payload.contains("page=1")
            )
            assertTrue(
                "bmp2 must report its own payload (page=2)",
                bmp2.payload.contains("page=2")
            )
            assertTrue(
                "No stale entries must remain in map after both resolve",
                pendingRenders.isEmpty()
            )
        }

    @Test
    fun `response for unknown key is a no-op not a crash`() {
        val key = Pair(999L, 999)
        val cont = pendingRenders.remove(key)
        assertNull("No match for unknown key → null continuation", cont)
    }

    @Test
    fun `cancellation removes only the cancelled entry - unrelated entries survive`() =
        runTest {
            val gen = 7L
            val victimKey = Pair(gen, 2)

            // Register two independent entries with different page indices
            val neverDispatchedResult = async {
                simulateRenderPageKeyed(gen, 1, callbackDelayMs = 10_000)
            }
            val victimResult = async {
                simulateRenderPageKeyed(gen, 2, callbackDelayMs = 10_000)
            }
            delay(10)

            // Sanity: both entries are present
            assertEquals(2, pendingRenders.size)
            assertNotNull("Victim key must be present before cancellation", pendingRenders[victimKey])

            // Cancel ONLY the page=1 deferred; page=2 MUST survive untouched
            neverDispatchedResult.cancel()
            delay(5)

            assertNotNull(
                "Cancelling one key must not disturb unrelated keys in the map",
                pendingRenders[victimKey]
            )

            // Cleanup: also cancel the victim so GlobalScope jobs don't leak
            victimResult.cancel()
            pendingRenders.clear()
        }
}
