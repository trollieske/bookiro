package com.bookrio.core.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class BookTitleCleanerTest {

    @Test
    fun stripsZeroPaddedIndexes() {
        assertEquals("The Sword of Shannara", BookTitleCleaner.clean("01 The Sword of Shannara"))
        assertEquals("First King of Shannara", BookTitleCleaner.clean("00 First King of Shannara"))
        assertEquals("Red Country", BookTitleCleaner.clean("06 Red Country"))
        assertEquals("Indomitable", BookTitleCleaner.clean("03.25 Indomitable"))
    }

    @Test
    fun leavesRealNumericTitles() {
        assertEquals("1984", BookTitleCleaner.clean("1984"))
        assertEquals("2001: A Space Odyssey", BookTitleCleaner.clean("2001: A Space Odyssey"))
        assertEquals("20,000 Leagues Under the Sea", BookTitleCleaner.clean("20,000 Leagues Under the Sea"))
        assertEquals("11/22/63", BookTitleCleaner.clean("11/22/63"))
        assertEquals("7 Habits of Highly Effective People", BookTitleCleaner.clean("7 Habits of Highly Effective People"))
        assertEquals("The 7 Habits of Highly Effective People", BookTitleCleaner.clean("The 7 Habits of Highly Effective People"))
    }

    @Test
    fun leavesNormalTitlesAlone() {
        assertEquals("Dune", BookTitleCleaner.clean("Dune"))
        assertEquals("The Hobbit", BookTitleCleaner.clean("The Hobbit"))
        assertEquals("", BookTitleCleaner.clean("   "))
    }
}