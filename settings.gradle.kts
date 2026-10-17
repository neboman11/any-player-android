pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx prebuilt AAR (no Maven artifact), fetched by :dj:downloadSherpaOnnxAar.
        // A repository dependency (not files()) so :dj can still be bundled as an AAR.
        flatDir {
            dirs("dj/libs")
        }
    }
}

rootProject.name = "any-player-android"
include(":core:model")
include(":core:data")
include(":playback")
include(":dj")
include(":app")
