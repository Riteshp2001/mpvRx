plugins {
  alias(libs.plugins.android.application)
}

val releaseVersionCode = rootProject.extra["releaseVersionCode"] as Int
val releaseVersionName = rootProject.extra["releaseVersionName"] as String
val runtimePackVersionCode = project.findProperty("runtimePackVersionCode")?.toString()?.toIntOrNull() ?: releaseVersionCode
val runtimePackVersionName = project.findProperty("runtimePackVersionName")?.toString() ?: "v$releaseVersionName"

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
