// AGP 9 applique lui-même le plugin Kotlin (« built-in Kotlin ») : le plugin
// `org.jetbrains.kotlin.android` ne doit plus être appliqué. AGP 9.3.1 embarque
// KGP 2.2.10 ; on force ici la dernière version stable de Kotlin et le KSP
// correspondant, ce qui est la procédure documentée pour monter de version.
// https://developer.android.com/build/releases/agp-9-0-0-release-notes
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:${libs.versions.ksp.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}
