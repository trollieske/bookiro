plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.compose") version "1.8.2" apply false
    id("com.google.devtools.ksp") version "2.1.20-1.0.32" apply false
    id("androidx.navigation.safeargs.kotlin") version "2.8.4" apply false
}

subprojects {
    configurations.all {
        resolutionStrategy {
            dependencySubstitution {
                substitute(module("org.bouncycastle:bcprov-jdk15on"))
                    .using(module("org.bouncycastle:bcprov-jdk18on:1.80"))
                substitute(module("org.bouncycastle:bcpkix-jdk15on"))
                    .using(module("org.bouncycastle:bcpkix-jdk18on:1.80"))
                substitute(module("org.bouncycastle:bcprov-jdk15to18"))
                    .using(module("org.bouncycastle:bcprov-jdk18on:1.80"))
            }
        }
    }
}
