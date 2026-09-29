import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    // iOS-only shared UI module. Exposed to Xcode as a static framework named
    // "Shared". Android keeps consuming :designsystem/:library directly.
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { target: KotlinNativeTarget ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            // Export the shared core so Swift can see its models too.
            export(project(":core"))
            export(project(":data"))
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                api(project(":core"))
                api(project(":data"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}