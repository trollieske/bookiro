package com.bookrio.core.dispatchers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Kotlin/Native has no public `Dispatchers.IO`; `Dispatchers.Default` is the
 * shared multi-threaded pool and is the correct substitute for file/network work.
 */
actual val platformIoDispatcher: CoroutineDispatcher = Dispatchers.Default