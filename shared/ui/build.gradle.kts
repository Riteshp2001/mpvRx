plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.compose.compiler)
  alias(libs.plugins.compose.multiplatform)
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

  compilerOptions {
    optIn.addAll(
      "androidx.compose.material3.ExperimentalMaterial3Api",
      "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
      "androidx.compose.foundation.ExperimentalFoundationApi",
    )
  }

  sourceSets {
    commonMain.dependencies {
      api(project(":shared:core"))
      api(libs.cmp.runtime)
      api(libs.cmp.foundation)
      api(libs.cmp.ui)
      api(libs.cmp.components.resources)
      api(libs.cmp.material3)
      api(libs.composables.material.symbols.rounded.filled)
    }
  }
}

compose.resources {
  publicResClass = true
  packageOfResClass = "app.gyrolet.mpvrx.ui.resources"
  generateResClass = always
}
