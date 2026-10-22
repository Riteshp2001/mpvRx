plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlinx.serialization)
}

// Apple targets need a macOS host; opt in with -Pmpvrx.enableIos=true.
val enableIos = providers.gradleProperty("mpvrx.enableIos").orNull?.toBoolean() == true

kotlin {
  jvmToolchain(17)

  jvm("desktop")

  if (enableIos) {
    iosX64()
    iosArm64()
    iosSimulatorArm64()
  }

  sourceSets {
    commonMain.dependencies {
      api(libs.kotlinx.coroutines.core)
      api(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
    }
  }
}
