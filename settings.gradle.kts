pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
        // KSP is published to the Plugin Portal and Maven Central, never Google Maven.
        excludeGroup("com.google.devtools.ksp")
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
    // KodiLibAndroid. Migrated prereleases retain their original mpvlib-kodi filenames.
    exclusiveContent {
      forRepository {
        ivy {
          name = "kodiLibAndroidGitHubReleases"
          url = uri("https://github.com/Riteshp2001/kodilibandroid/releases/download")
          patternLayout {
            artifact("kodi-v[revision]/kodilibandroid-[revision].[ext]")
            artifact("kodi-v[revision]/[artifact]-[revision].[ext]")
          }
          metadataSources {
            artifact()
          }
        }
      }
      filter {
        includeGroup("app.gyrolet.mpvlib.kodi")
      }
    }
    maven(url = "https://www.jitpack.io") {
      content {
        // Only use JitPack for specific dependencies to avoid unnecessary checks
        includeGroup("io.github.abdallahmehiz")
        includeGroup("com.github.abdallahmehiz")
        includeGroup("com.github.K1rakishou")
        includeGroup("com.github.thegrizzlylabs")
      }
    }
  }
}

rootProject.name = "mpvRx"
include(":app")
