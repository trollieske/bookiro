package com.bookrio.library.sort

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards that gamification is gone from all production source:
 * 15. No production UI path subscribes to tierEvents or invokes SaluteEffect.
 * 16. Former goal/streak code cannot trigger a visible animation (all its
 *     UI consumers/producers are deleted; legacy data stays dormant).
 */
class GamificationRemovalTest {

    private val forbidden = listOf(
        "tierEvents",
        "SaluteEffect",
        "SaluteTier",
        "rememberSaluteEffectState",
        "SaluteEffectOverlay",
        "ReadingRhythmViewModel",
        "LeserytmeWidget",
        "debugTriggerTier",
        "debugSimulateGoalReached",
        "debugAddActiveSeconds",
        "rhythmCelebrationsEnabled",
        "rhythmDebugAutoTrigger"
    )

    private fun sourceRoots(): List<File> {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            if (File(dir, "settings.gradle.kts").exists()) return@repeat
            dir = dir.parentFile ?: dir
        }
        val root = dir
        return listOf(
            File(root, "app/src/main/java"),
            File(root, "library/src/main/java"),
            File(root, "core/src/main/java"),
            File(root, "designsystem/src/main/java")
        ).filter { it.exists() }
    }

    @Test
    fun `no production source references gamification symbols`() {
        val roots = sourceRoots()
        assertTrue("Could not locate source roots", roots.size == 4)
        val violations = mutableListOf<String>()
        roots.forEach { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                val text = f.readText()
                forbidden.forEach { token ->
                    if (token in text) violations.add("${f.path} contains '$token'")
                }
            }
        }
        assertTrue(
            "Gamification references remain:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `gamification ui package is deleted`() {
        val roots = sourceRoots()
        val gamificationDirs = roots.map { File(it, "com/bookrio/library/gamification") }
            .filter { it.exists() }
        assertFalse(
            "library/gamification package still exists: $gamificationDirs",
            gamificationDirs.isNotEmpty()
        )
    }
}
