import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.navigation.safeargs.kotlin")
}

// ─── Production signing (never commit secrets) ────────────────────────────────
// Credentials are read from (in order): environment variables, then an ignored
// `keystore.properties` at the repo root. See docs/RELEASE_SIGNING.md.
// A release build FAILS clearly when neither production credentials nor an
// explicit `BOOKIRO_ALLOW_DEBUG_SIGNING=true` opt-in is present, so a debug-signed
// artifact can never be mistaken for a Play-ready build.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
fun signingValue(env: String, property: String): String? =
    System.getenv(env) ?: keystoreProperties.getProperty(property)

val releaseStorePath = signingValue("BOOKIRO_KEYSTORE_PATH", "storeFile")
val releaseStorePassword = signingValue("BOOKIRO_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("BOOKIRO_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("BOOKIRO_KEY_PASSWORD", "keyPassword")
val hasProductionSigning = !releaseStorePath.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank() &&
    rootProject.file(releaseStorePath).exists()
val allowDebugSigning = System.getenv("BOOKIRO_ALLOW_DEBUG_SIGNING")?.toBoolean() == true
val releaseRequested = gradle.startParameter.taskNames.any { name ->
    val task = name.substringAfterLast(':')
    val packaging = task.startsWith("assemble") || task.startsWith("bundle") ||
        task.startsWith("package") || task.startsWith("install") || task == "build"
    val nonPackaging = task.startsWith("lint") || task.startsWith("compile") ||
        task.startsWith("test") || task.endsWith("UnitTest")
    packaging && !nonPackaging && (task.contains("Release") || task == "assemble" || task == "bundle" || task == "build")
}

android {
    namespace = "com.bookrio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bookrio"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.0.0-readium9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            if (releaseRequested && !hasProductionSigning && !allowDebugSigning) {
                throw GradleException(
                    "Production release signing is not configured. Provide " +
                        "BOOKIRO_KEYSTORE_PATH / BOOKIRO_KEYSTORE_PASSWORD / " +
                        "BOOKIRO_KEY_ALIAS / BOOKIRO_KEY_PASSWORD (env or ignored " +
                        "keystore.properties). For a NON-production test artifact only, " +
                        "set BOOKIRO_ALLOW_DEBUG_SIGNING=true to accept debug signing. " +
                        "See docs/RELEASE_SIGNING.md."
                )
            }
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasProductionSigning) {
                signingConfigs.getByName("release")
            } else {
                // Only reachable with the explicit BOOKIRO_ALLOW_DEBUG_SIGNING opt-in;
                // such an artifact is TEST-only and must never be published as Play-ready.
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasProductionSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-Xjvm-default=all",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlin.RequiresOptIn"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation(project(":core"))
    implementation(project(":designsystem"))
    implementation(project(":data"))
    implementation(project(":library"))
    implementation(project(":reader"))
    implementation(project(":player"))
    implementation(project(":ftp"))
    implementation(project(":smb"))
    implementation(project(":webdav"))
    implementation(project(":calibre"))
    implementation(project(":torrent"))
    implementation(project(":podcast"))

    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.navigation.runtime.ktx)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.guava.android)
    // The reader no longer uses the vendored :pagecurl module (removed in favour of a
    // simple single-surface page turn). The module is deliberately left unwired for
    // rollback; do NOT add it (or the Maven artifact) as a dependency here — it
    // duplicates eu.wewox.pagecurl classes and can break the R8/minified release build.

    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.coil.gif)
    implementation(libs.androidx.startup.runtime)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.media3.session)
    androidTestImplementation(libs.androidx.media3.common)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

configurations.all {
    resolutionStrategy {
        force("org.bouncycastle:bcprov-jdk18on:1.80")
        force("androidx.startup:startup-runtime:1.2.0")
        dependencySubstitution {
            substitute(module("org.bouncycastle:bcprov-jdk15on")).using(module("org.bouncycastle:bcprov-jdk18on:1.80"))
            substitute(module("org.bouncycastle:bcprov-jdk15to18")).using(module("org.bouncycastle:bcprov-jdk18on:1.80"))
        }
    }
}
