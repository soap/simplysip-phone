// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.spotless)
}

// Formatting for the whole repository: ./gradlew spotlessCheck (verify) / spotlessApply (fix).
// ktlint rules and line length come from .editorconfig.
spotless {
    val ktlintVersion = libs.versions.ktlint.get()
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "sip/pjsip-native/**")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target("*.gradle.kts", "**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(ktlintVersion)
    }
    format("misc") {
        target("*.md", "docs/**/*.md", ".github/**/*.yml", ".gitignore", ".gitattributes", ".editorconfig")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
