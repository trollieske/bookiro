package com.bookrio.core.db

/**
 * Policy for reacting to a database open failure.
 *
 * Bookiro must **never** delete a user's database automatically. A transient or
 * corrupt open is recoverable: the file is kept and the app surfaces a retryable
 * error. Destruction is only ever allowed after an explicit, user-confirmed reset.
 *
 * Kept as a pure object so the invariant is unit-testable without Android.
 */
object DatabaseRecoveryPolicy {

    /** Automatic (not user-initiated) recovery must never delete the database file. */
    fun shouldDeleteDatabaseAutomatically(): Boolean = false

    /** Deletion is allowed only when the user has explicitly confirmed it. */
    fun mayDeleteDatabase(userConfirmedReset: Boolean): Boolean = userConfirmedReset
}