plugins {
  alias(libs.plugins.android.application)
}

val runtimePackVersionCode = project.findProperty("runtimePackVersionCode")?.toString()?.toIntOrNull() ?: 1
val runtimePackVersionName = project.findProperty("runtimePackVersionName")?.toString() ?: "dev"

android {
  namespace = "app.gyrolet.mpvrx.runtime.visual"
  compileSdk = 37

  defaultConfig {
    applicationId = "app.gyrolet.mpvrx.runtime.visual"
    minSdk = 26
    targetSdk = 36
    versionCode = runtimePackVersionCode
    versionName = runtimePackVersionName
  }

  buildFeatures { buildConfig = false }
}
