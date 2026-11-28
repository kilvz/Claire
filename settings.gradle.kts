pluginManagement {
    repositories {
        google()
        mavenCentral()
        // Maven Central occasionally rate-limits or times out GitHub Actions; the GCS mirror is the fallback
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Maven Central occasionally rate-limits or times out GitHub Actions; the GCS mirror is the fallback
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
    }
}
rootProject.name = "Claire"
include(":core", ":app")
