plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.bookrio.reader"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    buildFeatures { compose = true }

    testOptions {
        unitTests {
            // Robolectric trenger merged Android-resurser for JVM-tester
            // (syntetisk EPUB gjennom BookLoaderEngine + Room in-memory).
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    implementation(project(":core"))
    implementation(project(":designsystem"))
    implementation(project(":data"))

    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.core.ktx)

    // Readium Kotlin Toolkit 3.0.3 is the newest released line built against
    // Kotlin 1.9.24 metadata (readable by this repo's pinned Kotlin 2.0.21);
    // 3.1.2 (Kotlin 2.1.21), 3.2/3.3 (2.3.20) and 3.4 (2.4.20) are not readable
    // without a project-wide toolchain upgrade. See reader/README-READIUM.md.
    implementation("org.readium.kotlin-toolkit:readium-shared:3.0.3")
    implementation("org.readium.kotlin-toolkit:readium-streamer:3.0.3")
    implementation("org.readium.kotlin-toolkit:readium-navigator:3.0.3")
    // Hosts the stable EpubNavigatorFragment inside the Compose tree (no alpha
    // Compose navigator is used).
    implementation("androidx.fragment:fragment-compose:1.8.7")
    implementation("androidx.fragment:fragment-ktx:1.8.7")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // JVM-test av hele EPUB-lasteveien (BookLoaderEngine → parseEpub → kapittel-
    // HTML). Robolectric gir ekte Android-rammeverk (XmlPullParser, Uri, Log) og
    // Room in-memory-database; versjonen holdes som literal fordi den ikke
    // finnes i versjonskatalogen.
    testImplementation(libs.androidx.room.runtime)
    testImplementation("org.robolectric:robolectric:4.14.1")
}
