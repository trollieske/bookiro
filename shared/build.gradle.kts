import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("org.jetbrains.kotlin.plugin.compose")
}

// NOTE: the org.jetbrains.compose Gradle plugin is intentionally NOT applied here.
// Its `syncComposeResourcesForIos` task fails under the Xcode run-script environment
// and we do not use Compose resources. We depend on the Compose Multiplatform
// artifacts directly instead, keeping the Compose compiler plugin active.
kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { target: KotlinNativeTarget ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            export(project(":core"))
            export(project(":data"))
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:${libs.versions.composeMultiplatform.get()}")
                implementation("org.jetbrains.compose.foundation:foundation:${libs.versions.composeMultiplatform.get()}")
                implementation("org.jetbrains.compose.material3:material3:${libs.versions.composeMultiplatform.get()}")
                implementation("org.jetbrains.compose.ui:ui:${libs.versions.composeMultiplatform.get()}")
                implementation("org.jetbrains.compose.animation:animation:${libs.versions.composeMultiplatform.get()}")
                api(project(":core"))
                api(project(":data"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}