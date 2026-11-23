pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Portal plugins.gradle.org me-redirect marker AGP ke Maven Central (303),
    // padahal Google tidak memublikasikan marker itu di Central (404) — hanya
    // implementasinya di repo Google. Lewati marker dan ambil modul langsung
    // (versi sama, artefak sama, perilaku build tidak berubah).
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.application") {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NadaTube"
include(":app")
include(":core")
include(":desktop")
