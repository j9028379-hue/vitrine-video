package com.vitrinevideo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T3 must follow the exact same click-driven orange state as T1, T2 and T4. */
class CommerceFormatSelectionTest {

    private fun assetsRoot(): File {
        val candidates = listOf(File("src/main/assets"), File("vitrine-video-app/src/main/assets"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("assets root not found from ${File(".").absolutePath}")
    }

    @Test
    fun allFourCommerceCardsShareOneNeutralRestStateAndClickSelection() {
        val html = File(assetsRoot(), "mockup/vitrine-video-maquette.html").readText()
        val css = File(assetsRoot(), "mockup/android-host.css").readText()
        val cardTags = Regex(
            "<button class=\"format-card\"[^>]*data-format=\"([^\"]+)\"[^>]*data-sector=\"([^\"]+)\""
        ).findAll(html).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(
            listOf("t1" to "commerce", "t2" to "commerce", "t3" to "commerce", "t4" to "commerce"),
            cardTags.filter { it.second == "commerce" }.toList()
        )
        assertFalse(Regex("<button class=\"format-card[^\"]*is-selected").containsMatchIn(html))
        assertTrue(html.contains("selectedFormat: null"))
        assertTrue(html.contains("function applyFormatCardSelection()"))
        assertTrue(html.contains("card.classList.toggle(\"is-selected\", card.dataset.format===state.selectedFormat)"))
        assertTrue(html.contains(".format-card.is-selected"))
        assertTrue(html.contains("state.selectedFormat=selectedFormat"))
        assertFalse(css.contains("[data-format=\"t3\"]"))
        listOf("t1", "t2", "t3", "t4").forEach { selected ->
            assertTrue(html.contains("selectedFormat===\"$selected\""))
            val active = cardTags.filter { it.first == selected }.map { it.first }
            assertEquals(listOf(selected), active)
        }
    }
}
