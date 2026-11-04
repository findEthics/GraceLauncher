package com.galaxyrio.gracelauncher.magic

import org.junit.Assert.assertEquals
import org.junit.Test

class MagicPackagePriorityTest {
    @Test fun preferredPackageSurvivesCapAndKeepsStableBucketOrder() {
        val input = List(45) { "other:$it" } + listOf("preferred:1", "preferred:2")
        val sorted = prioritizeMagicPackages(input, "preferred") { it.substringBefore(':') }.take(40)
        assertEquals(listOf("preferred:1", "preferred:2", "other:0"), sorted.take(3))
        assertEquals(input.take(40), prioritizeMagicPackages(input, null) { it.substringBefore(':') }.take(40))
    }
}
