pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Narratify"

include(":shared:domain")
include(":shared:data")
include(":shared:playback")
include(":shared:text")
include(":shared:align")
include(":androidApp")
