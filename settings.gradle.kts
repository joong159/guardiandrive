pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Pleos Connect SDK 저장소 (document.pleos.ai › Vehicle SDK › Project setup)
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}
rootProject.name = "GuardianDrive"
include(":core", ":app")
