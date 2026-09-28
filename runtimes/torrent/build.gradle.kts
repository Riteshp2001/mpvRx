plugins {
  alias(libs.plugins.android.application)
}

val targetAbiProp = project.findProperty("targetAbi")?.toString()
val enableX86 = project.findProperty("enableX86") != "false"
val runtimePackVersionCode = project.findProperty("runtimePackVersionCode")?.toString()?.toIntOrNull() ?: 1
val runtimePackVersionName = project.findProperty("runtimePackVersionName")?.toString() ?: "dev"
val activeAbis =
  targetAbiProp
    ?.takeIf(String::isNotBlank)
    ?.split(",")
    ?.map(String::trim)
    ?: listOf("arm64-v8a", "armeabi-v7a") + if (enableX86) listOf("x86", "x86_64") else emptyList()

android {
  namespace = "app.gyrolet.mpvrx.runtime.torrent"
  compileSdk = 37

  defaultConfig {
    applicationId = "app.gyrolet.mpvrx.runtime.torrent"
    minSdk = 26
    targetSdk = 36
    versionCode = runtimePackVersionCode
    versionName = runtimePackVersionName
  }

  splits {
    abi {
      isEnable = true
      reset()
      include(*activeAbis.toTypedArray())
      isUniversalApk = false
    }
  }

  buildTypes.named("release") { isMinifyEnabled = true }
  buildFeatures { buildConfig = false }
  packaging { jniLibs.useLegacyPackaging = true }
}

dependencies {
  runtimeOnly(libs.libtorrent4j.android.arm64)
  runtimeOnly(libs.libtorrent4j.android.arm)
  if (enableX86) {
    runtimeOnly(libs.libtorrent4j.android.x86)
    runtimeOnly(libs.libtorrent4j.android.x8664)
  }
}
