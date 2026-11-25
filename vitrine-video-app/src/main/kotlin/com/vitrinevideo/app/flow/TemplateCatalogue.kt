package com.vitrinevideo.app.flow

/**
 * One authoritative list of template IDs shipped in this APK. Commerce T1-T4
 * remain unchanged; Restauration T2 is added with its own ID so no domain
 * collides with COMMERCE-T2. No Restauration T4 exists in this release.
 */
object TemplateCatalogue {
    const val RESTAURATION_T2 = "RESTAURATION-T2"

    val SUPPORTED_IDS: Set<String> = setOf(
        "COMMERCE-T1",
        "COMMERCE-T2",
        "COMMERCE-T3",
        "COMMERCE-T4",
        RESTAURATION_T2
    )

    fun isSupported(templateId: String): Boolean = templateId in SUPPORTED_IDS
}
