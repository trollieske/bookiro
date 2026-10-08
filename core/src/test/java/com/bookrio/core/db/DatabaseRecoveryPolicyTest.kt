package com.bookrio.core.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the data-loss fix: an automatic database-open failure
 * must never delete the user's database, and deletion is only allowed after an
 * explicit user confirmation.
 */
class DatabaseRecoveryPolicyTest {

    @Test
    fun `an open failure never deletes the database automatically`() {
        assertFalse(DatabaseRecoveryPolicy.shouldDeleteDatabaseAutomatically())
    }

    @Test
    fun `deletion is refused without explicit user confirmation`() {
        assertFalse(DatabaseRecoveryPolicy.mayDeleteDatabase(userConfirmedReset = false))
    }

    @Test
    fun `deletion is only allowed after explicit user confirmation`() {
        assertTrue(DatabaseRecoveryPolicy.mayDeleteDatabase(userConfirmedReset = true))
    }
}