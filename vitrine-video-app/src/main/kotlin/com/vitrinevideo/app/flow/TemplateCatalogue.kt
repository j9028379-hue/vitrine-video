package com.vitrinevideo.app.flow

/**
 * One authoritative list of template IDs shipped in this APK. Commerce T1-T4
 * remain unchanged. Restauration keeps T1, T2, T3 and T5: there is no
 * Restauration T4 testimonial and no new domain or separate app.
 */
object TemplateCatalogue {
    const val RESTAURATION_T2 = "RESTAURATION-T2"
    const val RESTAURATION_T5 = "RESTAURATION-T5"

    val SUPPORTED_IDS: Set<String> = setOf(
        "COMMERCE-T1",
        "COMMERCE-T2",
        "COMMERCE-T3",
        "COMMERCE-T4",
        RESTAURATION_T2,
        RESTAURATION_T5
    )

    fun isSupported(templateId: String): Boolean = templateId in SUPPORTED_IDS

    /** Merchant-facing names; internal IDs must stay out of camera/render UI. */
    fun displayName(templateId: String, french: Boolean = true): String = when (templateId) {
        "COMMERCE-T1" -> if (french) "Présentation de mon commerce" else "My business presentation"
        "COMMERCE-T2" -> if (french) "Nouvel arrivage" else "New arrival"
        "COMMERCE-T3" -> if (french) "Démonstration" else "Demonstration"
        "COMMERCE-T4" -> if (french) "Promotion" else "Special offer"
        RESTAURATION_T2 -> if (french) "Avant / Après" else "Before / After"
        RESTAURATION_T5 -> "Formule, combo et offre"
        else -> if (french) "Votre vidéo" else "Your video"
    }
}
