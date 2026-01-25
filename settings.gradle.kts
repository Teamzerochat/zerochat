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
        maven { url = uri("https://jitpack.io") }
        // SQLCipher repository
        maven { url = uri("https://oss.sonatype.org/content/repositories/releases/") }
    }
}

rootProject.name = "ZeroChat"
include(":app")
