// Top-level build file where you can add configuration options common to all sub-projects/modules.
val releaseVersionName = "2.7.0"
val releaseVersionCode = 262

extra["releaseVersionName"] = releaseVersionName
extra["releaseVersionCode"] = releaseVersionCode

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose.compiler) apply false
  alias(libs.plugins.kotlinx.serialization) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.room) apply false
  alias(libs.plugins.ktlint) apply false
}

tasks.register("printReleaseVersion") {
  group = "versioning"
  description = "Prints the authoritative release version name and code."
  doLast {
    println("$releaseVersionName $releaseVersionCode")
  }
}
