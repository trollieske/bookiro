package com.bookrio.core.dispatchers

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Platform IO dispatcher. `Dispatchers.IO` is JVM/Android-only (internal on
 * Kotlin/Native), so the OS boundary exposes it as an expect/actual.
 */
expect val platformIoDispatcher: CoroutineDispatcher