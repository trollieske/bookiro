package com.bookrio.reader.readium

import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.streamer.PublicationOpener

/**
 * Compile-only probe proving that the repository's pinned Kotlin 2.0.21 toolchain
 * can consume Readium Kotlin Toolkit 3.0.3 (Kotlin 1.9.24 metadata) without
 * disabling metadata validation. If this file compiles, the metadata is readable.
 */
internal object ReadiumCompatProbe {
    @Suppress("unused")
    fun symbols(): List<Class<*>> = listOf(
        Publication::class.java,
        PublicationOpener::class.java,
        EpubNavigatorFragment::class.java,
        EpubNavigatorFactory::class.java,
        Locator::class.java,
    )
}