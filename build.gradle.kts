plugins {
    alias(libs.plugins.android.application) apply false
    // Declared (not applied) so the pinned Kotlin version is the one AGP's built-in Kotlin uses.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
