// Every plugin is declared here (apply false) so they all load in one classloader. This is
// also what makes AGP's built-in Kotlin use the catalog's KGP version instead of the 2.2.10
// floor AGP 9 depends on.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}
