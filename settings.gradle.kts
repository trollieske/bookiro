pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Bookrio"

include(":app")
include(":core")
include(":designsystem")
include(":data")
include(":library")
include(":reader")
include(":player")
include(":ftp")
include(":smb")
include(":webdav")
include(":calibre")
include(":torrent")
include(":pagecurl")
include(":podcast")
