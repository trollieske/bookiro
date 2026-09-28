-keepattributes *Annotation*
-keep class com.bookrio.data.** { *; }
-keep class androidx.compose.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn org.jetbrains.annotations.**

# libtorrent4j JNI — keep so ProGuard doesn't strip the native bridge
-keep class org.libtorrent4j.swig.libtorrent_jni { *; }
-keep class org.libtorrent4j.** { *; }

# SLF4J / Logging / Security fallbacks used by SMB, FTP and SSH libraries
-dontwarn org.slf4j.**
-dontwarn org.apache.commons.logging.**
-dontwarn org.bouncycastle.**
-dontwarn sun.security.**
-dontwarn net.i2p.**
-keep class org.slf4j.** { *; }
