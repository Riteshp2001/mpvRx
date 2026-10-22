import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.compose.compiler)
  alias(libs.plugins.compose.multiplatform)
}

kotlin {
  jvmToolchain(17)
  compilerOptions {
    optIn.addAll(
      "androidx.compose.material3.ExperimentalMaterial3Api",
      "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
      "androidx.compose.foundation.ExperimentalFoundationApi",
    )
  }
}

dependencies {
  implementation(project(":shared:ui"))
  implementation(compose.desktop.currentOs)
  implementation(libs.kotlinx.coroutines.swing)
  implementation(libs.jna)
  implementation(libs.jna.platform)
  testImplementation(libs.kotlin.test)
}

tasks.test {
  // Lets the libmpv smoke test find desktopApp/native/<os>-<arch>.
  workingDir = projectDir
}

val appVersion =
  rootProject.file("app/build.gradle.kts").readText().let { script ->
    Regex("""versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)""").find(script)?.groupValues?.get(1)
  } ?: "1.0.0"

compose.desktop {
  application {
    mainClass = "app.gyrolet.mpvrx.desktop.MainKt"
    jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Dsun.java2d.uiScale.enabled=true", "-Dmpvrx.version=$appVersion")

    // JNA binds libmpv through reflection-generated proxies, which ProGuard would strip.
    buildTypes.release.proguard {
      isEnabled.set(false)
    }

    nativeDistributions {
      targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.AppImage, TargetFormat.Dmg)
      packageName = "mpvRx"
      packageVersion = appVersion
      description = "mpvRx — mpv based media player"
      vendor = "Gyrolet"
      copyright = "AGPL-3.0-or-later"
      licenseFile.set(rootProject.file("LICENSE"))
      // libmpv and friends are bundled per OS from desktopApp/native/<os>/ (see scripts/fetch-libmpv-windows.ps1).
      appResourcesRootDir.set(project.layout.projectDirectory.dir("native"))
      modules("java.desktop", "java.logging", "java.naming", "jdk.unsupported")

      windows {
        menuGroup = "mpvRx"
        upgradeUuid = "6f1d6a5e-2a9b-4d8e-9f53-3c6b0b7a9e21"
        dirChooser = true
        perUserInstall = true
        shortcut = true
        iconFile.set(project.layout.projectDirectory.file("icons/mpvrx.ico"))
      }
      linux {
        packageName = "mpvrx"
        iconFile.set(project.layout.projectDirectory.file("icons/mpvrx.png"))
      }
    }
  }
}
