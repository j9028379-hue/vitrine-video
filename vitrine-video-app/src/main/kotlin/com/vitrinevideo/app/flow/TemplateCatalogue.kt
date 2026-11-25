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

    /** Merchant-facing names; internal IDs must stay out of camera/render UI. */
    fun displayName(templateId: String, french: Boolean = true): String = when (templateId) {
        "COMMERCE-T1" -> if (french) "Présentation de mon commerce" else "My business presentation"
        "COMMERCE-T2" -> if (french) "Nouvel arrivage" else "New arrival"
        "COMMERCE-T3" -> if (french) "Démonstration" else "Demonstration"
        "COMMERCE-T4" -> if (french) "Promotion" else "Special offer"
        RESTAURATION_T2 -> if (french) "Avant / Après" else "Before / After"
        else -> if (french) "Votre vidéo" else "Your video"
    }
}
