import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

/**
 * Version unique de vérité pour la version de l'app.
 * Le workflow GitHub Actions dérive le tag `v<versionName>` de ces valeurs, et
 * l'updater in-app compare le `versionCode` local à celui publié dans la Release.
 */
val appVersionCode = 5
val appVersionName = "0.2.1"

/**
 * Signature release.
 * - En local : lue depuis `keystore.properties` (non versionné) si présent.
 * - En CI    : lue depuis les variables d'environnement alimentées par les GitHub Secrets.
 * Si aucune des deux n'est disponible, aucun signingConfig n'est attaché et le build
 * release échouera explicitement plutôt que de produire un APK non signé.
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use(::load)
}

fun secret(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

val releaseStorePath = secret("storeFile", "RESONATE_KEYSTORE_PATH")
val releaseStorePassword = secret("storePassword", "RESONATE_KEYSTORE_PASSWORD")
val releaseKeyAlias = secret("keyAlias", "RESONATE_KEY_ALIAS")
val releaseKeyPassword = secret("keyPassword", "RESONATE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "io.github.micferna.resonate"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "io.github.micferna.resonate"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Consommé par l'updater : d'où viennent les mises à jour.
        buildConfigField("String", "UPDATE_REPO_OWNER", "\"micferna\"")
        buildConfigField("String", "UPDATE_REPO_NAME", "\"resonate\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "/META-INF/INDEX.LIST",
                "/META-INF/*.SF",
                "/META-INF/*.DSA",
                "/META-INF/*.RSA",
                "/META-INF/BC*",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        // Pas de fichier de référence : les problèmes se corrigent, ils ne se
        // mettent pas de côté. Les deux exceptions assumées sont dans `lint.xml`.
        // AGP 9 génère systématiquement les rapports HTML/XML/SARIF ; la CI les
        // récupère depuis `app/build/reports/lint-results-*`.
    }

    // Ne pas embarquer la liste des dépendances (blob signé Google Play) : inutile
    // pour une distribution GitHub et nuisible à la reproductibilité du build.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Le code de l'app doit rester sans warning : ils remontent en erreur en CI.
        allWarningsAsErrors.set(providers.gradleProperty("resonate.strictWarnings").isPresent)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.bundles.media3)
    implementation(libs.media3.exoplayer.workmanager)
    implementation(libs.media3.inspector)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.sshj)
    implementation(libs.smbj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    // Tests instrumentés : ils valident le schéma Room et les cascades réelles de
    // SQLite, ce qu'un test JVM ne peut pas faire. Nécessitent un appareil ou un
    // émulateur, et ne tournent donc pas dans la CI par défaut.
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // Tests de migration : instrumentés par nature, ils rejouent les migrations
    // sur une base réellement écrite au format de la version précédente.
    androidTestImplementation(libs.androidx.room.testing)
}
