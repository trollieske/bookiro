package com.bookrio.core.dispatchers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injectable dispatcher provider. Keeps business code testable by swapping
 * all dispatchers to a TestDispatcher in unit tests.
 *
 * Default implementation delegates to the real [Dispatchers].
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher get() = Dispatchers.Main
    val immediate: CoroutineDispatcher get() = Dispatchers.Main.immediate
    val default: CoroutineDispatcher get() = Dispatchers.Default
    val io: CoroutineDispatcher get() = Dispatchers.IO
    val unconfined: CoroutineDispatcher get() = Dispatchers.Unconfined
}

object DefaultDispatcherProvider : DispatcherProvider
