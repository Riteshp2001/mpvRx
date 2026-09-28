pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
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
    exclusiveContent {
      forRepository {
        ivy {
          name = "mpvlibGitHubReleases"
          url = uri("https://github.com/Riteshp2001/mpvlibAndroid/releases/download")
          patternLayout {
            artifact("v[revision]/[artifact]-[revision].[ext]")
          }
          metadataSources {
            artifact()
          }
        }
      }
      filter {
        includeGroup("app.gyrolet.mpvlib")
      }
    }
    maven(url = "https://www.jitpack.io") {
      content {
        // Only use JitPack for specific dependencies to avoid unnecessary checks
        includeGroup("io.github.abdallahmehiz")
        includeGroup("com.github.abdallahmehiz")
        includeGroup("com.github.K1rakishou")
        includeGroup("com.github.marlboro-advance")
        includeGroup("com.github.thegrizzlylabs")
        includeGroup("com.github.nanihadesuka")
        includeGroup("com.github.TraceLTRC")
      }
    }
  }
}

rootProject.name = "mpvRx"
include(":app")
include(":runtimes:online")
include(":runtimes:torrent")
include(":runtimes:visual")
