package com.bookrio.podcast.ui

import com.bookrio.podcast.R
import com.bookrio.podcast.domain.PodcastCountries
import com.bookrio.podcast.domain.PodcastShortcut

/**
 * Local, resource-driven "Start Here" discovery shortcuts. These are search
 * shortcuts only: tapping one runs a normal iTunes search. Nothing is ever
 * auto-followed, and no RSS URL is hardcoded here.
 */
object PodcastShortcuts {

    /** Always-visible category shortcuts. */
    val base: List<PodcastShortcut> = listOf(
        PodcastShortcut("crime_docs", R.string.pod_shortcut_crime_docs, "crime documentary"),
        PodcastShortcut("history", R.string.pod_shortcut_history, "history"),
        PodcastShortcut("technology", R.string.pod_shortcut_technology, "technology"),
        PodcastShortcut("ai", R.string.pod_shortcut_ai, "artificial intelligence"),
        PodcastShortcut("football", R.string.pod_shortcut_football, "football"),
        PodcastShortcut("society_news", R.string.pod_shortcut_society_news, "society news"),
        PodcastShortcut("business", R.string.pod_shortcut_business, "business"),
        PodcastShortcut("comedy", R.string.pod_shortcut_comedy, "comedy"),
        PodcastShortcut("science", R.string.pod_shortcut_science, "science"),
        PodcastShortcut("longform", R.string.pod_shortcut_longform, "long form documentary")
    )

    /** Localized contextual shortcuts based on the selected storefront. */
    fun contextual(country: String): List<PodcastShortcut> {
        val code = country.uppercase()
        val norway = listOf(
            PodcastShortcut("no_crime", R.string.pod_shortcut_norwegian_crime, "norsk krim"),
            PodcastShortcut("no_docs", R.string.pod_shortcut_norwegian_docs, "norsk dokumentar"),
            PodcastShortcut("no_history", R.string.pod_shortcut_norwegian_history, "norsk historie")
        )
        val sweden = listOf(
            PodcastShortcut("sv_docs", R.string.pod_shortcut_swedish_docs, "svensk dokumentär"),
            PodcastShortcut("sv_history", R.string.pod_shortcut_swedish_history, "svensk historia")
        )
        val denmark = listOf(
            PodcastShortcut("dk_history", R.string.pod_shortcut_danish_history, "dansk historie"),
            PodcastShortcut("dk_docs", R.string.pod_shortcut_danish_docs, "dansk dokumentar")
        )
        val english = listOf(
            PodcastShortcut("true_crime", R.string.pod_shortcut_true_crime, "true crime"),
            PodcastShortcut("history_pod", R.string.pod_shortcut_history_podcast, "history podcast"),
            PodcastShortcut("ai_pod", R.string.pod_shortcut_ai_podcast, "AI podcast"),
            PodcastShortcut("tech_pod", R.string.pod_shortcut_technology_podcast, "technology podcast"),
            PodcastShortcut("longform_doc", R.string.pod_shortcut_longform_doc, "long-form documentary")
        )
        return when (code) {
            PodcastCountries.NORWAY -> norway
            PodcastCountries.SWEDEN -> sweden
            PodcastCountries.DENMARK -> denmark
            PodcastCountries.UNITED_STATES, PodcastCountries.UNITED_KINGDOM -> english
            else -> english
        }
    }

    /** All shortcuts for a country: contextual first, then the general categories. */
    fun forCountry(country: String): List<PodcastShortcut> = contextual(country) + base
}