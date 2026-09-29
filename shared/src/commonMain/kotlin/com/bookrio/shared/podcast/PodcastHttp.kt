package com.bookrio.shared.podcast

/**
 * Minimal HTTP GET returning the response body as text.
 *
 * The iOS actual runs off the main thread and decodes UTF-8 (falling back to
 * ISO-8859-1); it throws when the response is missing or empty. Podcasts are
 * streaming-only on iOS, so this is all the networking they need.
 */
internal expect suspend fun httpGetText(url: String): String

/** Reads a local file as UTF-8 text (CI fixture loading). */
internal expect suspend fun readLocalText(path: String): String

/**
 * Automation hook used by the GitHub Actions simulator smoke test.
 *
 * When the `BOOKRIO_AUTO_SUBSCRIBE_RSS` environment variable is set to a feed
 * URL, the app subscribes to it on launch so CI can exercise the podcast stack.
 * Returns null during normal runs.
 */
internal expect fun autoSubscribeRssUrl(): String?

/**
 * Automation hook used by the GitHub Actions simulator smoke test.
 *
 * When `BOOKRIO_AUTO_SUBSCRIBE_RSS_FILE` is set to a local RSS file, the app
 * parses it and stores it without any network access, so the smoke test does not
 * depend on the runner being able to host/serve HTTP. Returns null normally.
 */
internal expect fun autoSubscribeRssFile(): String?