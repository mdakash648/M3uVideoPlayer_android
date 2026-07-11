package com.mdaksh.m3uvideoplayer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzySearchTest {

    private val threshold = FuzzySearch.DEFAULT_THRESHOLD

    @Test
    fun `heavy misspellings still match Bachelor Point`() {
        val typos = listOf("bacalor point", "becelor point", "bachalar point", "bachelor point")
        for (typo in typos) {
            val score = FuzzySearch.similarity(typo, "Bachelor Point")
            assertTrue("'$typo' should match (score=$score)", score >= threshold)
        }
    }

    @Test
    fun `exact substring and prefix match strongly`() {
        assertTrue(FuzzySearch.similarity("point", "Bachelor Point") >= threshold)
        assertTrue(FuzzySearch.similarity("bach", "Bachelor Point") >= threshold)
    }

    @Test
    fun `unrelated text does not match`() {
        assertTrue(FuzzySearch.similarity("cricket highlights", "Bachelor Point") < threshold)
    }

    @Test
    fun `index ranks the closest title first`() {
        val titles = listOf("Bachelor Point", "Bachelor Party", "College Point", "Cricket Live")
        val index = FuzzyIndex.build(titles) { it }
        val results = index.search("bacalor point")
        assertTrue("expected at least one hit", results.isNotEmpty())
        assertEquals("Bachelor Point", results.first().item)
    }

    @Test
    fun `blank query returns nothing`() {
        val index = FuzzyIndex.build(listOf("Bachelor Point")) { it }
        assertTrue(index.search("   ").isEmpty())
    }
}
