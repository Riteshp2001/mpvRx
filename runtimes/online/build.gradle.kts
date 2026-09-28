plugins {
  alias(libs.plugins.android.application)
}

val targetAbiProp = project.findProperty("targetAbi")?.toString()
val enableX86 = project.findProperty("enableX86") != "false"
val releaseVersionCode = rootProject.extra["releaseVersionCode"] as Int
val releaseVersionName = rootProject.extra["releaseVersionName"] as String
val runtimePackVersionCode = project.findProperty("runtimePackVersionCode")?.toString()?.toIntOrNull() ?: releaseVersionCode
val runtimePackVersionName = project.findProperty("runtimePackVersionName")?.toString() ?: "v$releaseVersionName"
val activeAbis =
  targetAbiProp
    ?.takeIf(String::isNotBlank)
    ?.split(",")
    ?.map(String::trim)
    ?: listOf("arm64-v8a", "armeabi-v7a") + if (enableX86) listOf("x86", "x86_64") else emptyList()

android {
  namespace = "app.gyrolet.mpvrx.runtime.online"
  compileSdk = 37
  ndkVersion = "27.3.13750724"

  defaultConfig {
    applicationId = "app.gyrolet.mpvrx.runtime.online"
    minSdk = 26
    targetSdk = 36
    versionCode = runtimePackVersionCode
    versionName = runtimePackVersionName
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  splits {
    abi {
      isEnable = true
      reset()
      include(*activeAbis.toTypedArray())
      isUniversalApk = false
    }
  }

  buildFeatures { buildConfig = false }
  packaging { jniLibs.useLegacyPackaging = true }
}
