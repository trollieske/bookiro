# Bookrio — iOS app (iPhone + iPad)

Kotlin Multiplatform port of Bookrio. The iOS UI is Compose Multiplatform
(shared Kotlin) hosted from SwiftUI. This folder is a complete Xcode project.

## Run on a Mac

```bash
# 1. One-time: make gradlew executable if needed
chmod +x ../gradlew

# 2. Open the Xcode project
open iosApp.xcodeproj

# 3. Select the "iosApp" scheme and an iPhone or iPad simulator, then Run (⌘R).
#    Xcode runs ../gradlew :shared:embedAndSignAppleFrameworkForXcode first to
#    build the Shared.framework, then links it.
```

Command line:

```bash
xcodebuild -project iosApp.xcodeproj -scheme iosApp \
  -sdk iphonesimulator -configuration Debug build
```

- Bundle id: `com.bookrio.ios`
- Deployment target: iOS 15.0
- Targets: iPhone + iPad (`TARGETED_DEVICE_FAMILY = 1,2`)

## Structure

- `iosApp/` — SwiftUI shell (`iOSApp.swift`, `ContentView.swift`, `Info.plist`, assets).
- Framework: `:shared` (Kotlin Multiplatform + Compose Multiplatform) exports a
  static `Shared.framework`; Swift imports `Shared` and calls
  `MainViewControllerKt.MainViewController()`.

Requires Xcode 15+, JDK 17. Android Studio/Kotlin plugin handles the Gradle side.