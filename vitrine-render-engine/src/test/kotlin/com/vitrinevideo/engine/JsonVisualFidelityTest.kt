package com.vitrinevideo.engine

import com.vitrinevideo.engine.pkg.TemplatePackageLoader
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import org.json.JSONObject

class JsonVisualFidelityTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun t1JsonRgbValuesReachParsedTextWindowsAndD52ReadabilityValuesRemain() {
        val zip = TestAssetLocator.packageZip("COMMERCE-T1-2.0.0.zip")
        val pkg = TemplatePackageLoader.importZip(zip, tmp.newFolder("t1-visual-fidelity"))
        val contractFile = pkg.resolvePackageRelative("templates/commerce/commerce-t1/montage/contrat-candidat.json")
        val contract = JSONObject(contractFile.readText())
        val typography = contract.getJSONObject("typographie")
        val expectedInk = "#" + (0 until 3).joinToString("") { typography.getJSONArray("encre_rgb").getInt(it).toString(16).padStart(2, '0') }.uppercase()
        val expectedRelief = "#" + (0 until 3).joinToString("") { typography.getJSONArray("relief_rgb").getInt(it).toString(16).padStart(2, '0') }.uppercase()
        val windows = pkg.renderPlan.textOverlay!!.windows
        assertEquals(setOf(expectedInk), windows.mapNotNull { it.inkColorHex }.toSet())
        assertEquals(setOf(expectedRelief), windows.mapNotNull { it.reliefColorHex }.toSet())

        val detail = windows.first { it.fieldKey == "accroche" }
        assertEquals("#FFF5E0", detail.inkColorHex)
        assertEquals("#2D1C15", detail.reliefColorHex)
        assertEquals(74.0, detail.overrideStyle!!.fontSizePx, 0.001)
        assertEquals(168.0, detail.maxHeightPx!!, 0.001)
        assertEquals(2, detail.maxLines)
    }
}
