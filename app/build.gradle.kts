import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.FilterConfiguration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.util.Properties

// SMBJ-RPC uses a desktop-only exception for malformed replies. Remap the dependency's
// references before D8/R8 so decoding failures stay catchable IOExceptions on Android.
// This fixes the missing runtime type instead of suppressing R8's missing-class diagnostic.
abstract class SmbRpcExceptionCompatibility : AsmClassVisitorFactory<InstrumentationParameters.None> {
  override fun isInstrumentable(classData: ClassData): Boolean =
    classData.className.startsWith("com.rapid7.client.dcerpc.")

  override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
    ClassRemapper(nextClassVisitor, object : Remapper(Opcodes.ASM9) {
      override fun map(internalName: String): String =
        if (internalName == "java/rmi/UnmarshalException") {
          "app/gyrolet/mpvrx/data/network/client/SmbRpcDecodeException"
        } else internalName
    })
}

val localProperties =
  Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
      localFile.inputStream().use { load(it) }
    }
  }

val targetAbiProp = project.findProperty("targetAbi")?.toString() ?: localProperties.getProperty("targetAbi")
val enableX86 = project.findProperty("enableX86") != "false"
val isExperimentalBuild = project.findProperty("experimentalBuild")?.toString()?.toBoolean() == true
val x86Abis = if (enableX86) listOf("x86", "x86_64") else emptyList()
val activeAbis =
  if (!targetAbiProp.isNullOrBlank()) {
    targetAbiProp.split(",").map { it.trim() }
  } else {
    listOf("arm64-v8a", "armeabi-v7a") + x86Abis
  }
val universalOnlyDistributions = setOf("noVulkan", "fongmi", "playstore")
// Distributions outside Google Play. Play forbids downloaded executable code (yt-dlp) and torrent
// streaming, so only these flavors enable yt-dlp and ship yt-dlp and libtorrent native payloads.
val sideloadDistributions = setOf("standard", "noVulkan", "fongmi")
// AGP cannot build an app bundle while ABI splits produce multiple APKs (issuetracker 402800800).
val isBundleBuild =
  gradle.startParameter.taskNames.any { it.substringAfterLast(':').startsWith("bundle") }
val releaseVersionCode = 302
val versionCodeBandSize = 10_000
val stableVersionCode = releaseVersionCode * versionCodeBandSize + (versionCodeBandSize - 1)
val previewVersionCode =
  (releaseVersionCode + 1) * versionCodeBandSize +
    (getCommitCount().toIntOrNull() ?: 1).coerceIn(1, versionCodeBandSize - 2)

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.baselineprofile)
  alias(libs.plugins.kotlin.compose.compiler)
  alias(libs.plugins.kotlinx.serialization)
  alias(libs.plugins.ksp)
  alias(libs.plugins.room)
  alias(libs.plugins.ktlint)
}

androidComponents {
  onVariants(selector().all()) { variant ->
    variant.instrumentation.transformClassesWith(
      SmbRpcExceptionCompatibility::class.java,
      InstrumentationScope.ALL,
    ) {}
  }
}

android {
  namespace = "app.gyrolet.mpvrx"
  compileSdk = 37
  ndkVersion = "27.3.13750724"

  defaultConfig {
    applicationId = "app.gyrolet.mpvrx"
    minSdk = 26
    targetSdk = 36
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    // Stable occupies the top of its version band. Preview uses the next band's commit-count
    // offset, so Stable -> Preview -> newer Preview -> next Stable is always an Android upgrade.
    versionCode = stableVersionCode
    versionName = "3.0.2"

    vectorDrawables {
      useSupportLibrary = true
    }

    buildConfigField("String", "GIT_SHA", "\"${getCommitSha()}\"")
    buildConfigField("int", "GIT_COUNT", getCommitCount())

    externalNativeBuild {
      cmake {
        abiFilters += activeAbis
      }
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  flavorDimensions += "distribution"

  productFlavors {
    create("standard") {
      dimension = "distribution"
      buildConfigField("boolean", "IS_PLAY_STORE_BUILD", "false")
      buildConfigField("boolean", "ENABLE_UPDATE_FEATURE", "true")
      buildConfigField("String", "UPDATE_APK_VARIANT", "\"standard\"")
      buildConfigField("boolean", "SCOPED_STORAGE_ONLY", "false")
      buildConfigField("boolean", "MPV_SUPPORTS_VULKAN", "true")
      buildConfigField("boolean", "MPV_SUPPORTS_MEDIACODEC_VULKAN", "false")
    }

    create("playstore") {
      dimension = "distribution"
      buildConfigField("boolean", "IS_PLAY_STORE_BUILD", "true")
      buildConfigField("boolean", "ENABLE_UPDATE_FEATURE", "false")
      buildConfigField("String", "UPDATE_APK_VARIANT", "\"standard\"")
      buildConfigField("boolean", "SCOPED_STORAGE_ONLY", "true")
      buildConfigField("boolean", "MPV_SUPPORTS_VULKAN", "true")
      buildConfigField("boolean", "MPV_SUPPORTS_MEDIACODEC_VULKAN", "false")
    }

    create("noVulkan") {
      dimension = "distribution"
      buildConfigField("boolean", "IS_PLAY_STORE_BUILD", "false")
      buildConfigField("boolean", "ENABLE_UPDATE_FEATURE", "true")
      buildConfigField("String", "UPDATE_APK_VARIANT", "\"no-vulkan\"")
      buildConfigField("boolean", "SCOPED_STORAGE_ONLY", "false")
      buildConfigField("boolean", "MPV_SUPPORTS_VULKAN", "false")
      buildConfigField("boolean", "MPV_SUPPORTS_MEDIACODEC_VULKAN", "false")
    }

    create("fongmi") {
      dimension = "distribution"
      buildConfigField("boolean", "IS_PLAY_STORE_BUILD", "false")
      buildConfigField("boolean", "ENABLE_UPDATE_FEATURE", "true")
      buildConfigField("String", "UPDATE_APK_VARIANT", "\"fongmi\"")
      buildConfigField("boolean", "SCOPED_STORAGE_ONLY", "false")
      buildConfigField("boolean", "MPV_SUPPORTS_VULKAN", "true")
      buildConfigField("boolean", "MPV_SUPPORTS_MEDIACODEC_VULKAN", "true")
    }

    configureEach {
      val enableYtdlp = name in sideloadDistributions
      buildConfigField("boolean", "ENABLE_YTDLP", enableYtdlp.toString())
      buildConfigField("boolean", "ENABLE_TORRENT", enableYtdlp.toString())
      if (enableYtdlp) proguardFile("src/sideload/proguard-rules.pro")
      externalNativeBuild {
        cmake {
          arguments += "-DMPVRX_ENABLE_YTDLP=${if (enableYtdlp) "ON" else "OFF"}"
        }
      }
    }
  }

  sourceSets {
    sideloadDistributions.forEach { flavor ->
      getByName(flavor).apply {
        // AGP's built-in Kotlin requires custom Kotlin directories on this source set.
        kotlin.directories.add("src/sideload/java")
        manifest.srcFile("src/sideload/AndroidManifest.xml")
        assets.directories.add("src/ytdlp/assets")
        jniLibs.directories.add("src/ytdlp/jniLibs")
      }
    }
  }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = false
  }

  splits {
    abi {
      isEnable = !isBundleBuild
      reset()
      include(*activeAbis.toTypedArray())
      isUniversalApk = activeAbis.size > 1
    }
  }

  buildTypes {
    named("release") {
      buildConfigField("boolean", "IS_PREVIEW_BUILD", "false")
      buildConfigField("boolean", "IS_EXPERIMENTAL_BUILD", isExperimentalBuild.toString())
      if (isExperimentalBuild) versionNameSuffix = "-exp.r${getCommitCount()}"
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
      ndk {
        // Include native symbol tables in Play Store app bundles for crash/ANR diagnosis.
        // Keep the smaller symbol-table level instead of bundling full DWARF info.
        debugSymbolLevel = "symbol_table"
      }
    }

    create("preview") {
      initWith(getByName("release"))
      signingConfig = signingConfigs.getByName("debug")
      buildConfigField("boolean", "IS_PREVIEW_BUILD", "true")
      buildConfigField("boolean", "IS_EXPERIMENTAL_BUILD", "false")
      versionNameSuffix = "-beta.r${getCommitCount()}"
    }

    named("debug") {
      buildConfigField("boolean", "IS_PREVIEW_BUILD", "false")
      buildConfigField("boolean", "IS_EXPERIMENTAL_BUILD", "false")
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-${getCommitCount()}"
      resValue("string", "app_name", "mpvRx-Debug")
    }
  }

  compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    viewBinding = true
    buildConfig = true
    resValues = true
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      excludes += "META-INF/DEPENDENCIES"
      excludes += "META-INF/LICENSE*"
      excludes += "META-INF/NOTICE*"
      excludes += "META-INF/*.kotlin_module"
      excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
    jniLibs {
      useLegacyPackaging = true
      pickFirsts += "**/libc++_shared.so"
    }
  }

  @Suppress("UnstableApiUsage")
  androidResources {
    generateLocaleConfig = true
  }

  lint {
    checkReleaseBuilds = false
    abortOnError = false
  }
}

androidComponents {
  val abiCodes =
    mutableMapOf(
      "armeabi-v7a" to 1,
      "arm64-v8a" to 2,
    )
  if (enableX86) {
    abiCodes["x86"] = 3
    abiCodes["x86_64"] = 4
  }

  onVariants { variant ->
    val isUniversalOnly =
      variant.productFlavors.any { (dimension, flavor) ->
        dimension == "distribution" && flavor in universalOnlyDistributions
      }

    // Reserve suffix 6 for the Play Store stable build. This gives the compatibility
    // fix a fresh 3.0.2 version code (30299996) after the already-uploaded 30299990
    // and 30299995 bundles, without changing version codes for other distributions.
    val isPlayStoreRelease =
      variant.buildType == "release" &&
        !isExperimentalBuild &&
        variant.productFlavors.any { (dimension, flavor) ->
          dimension == "distribution" && flavor == "playstore"
        }

    variant.outputs.forEach { output ->
      val abi =
        output.filters
          .find { it.filterType == FilterConfiguration.FilterType.ABI }
          ?.identifier

      if (isUniversalOnly && abi != null) {
        output.enabled.set(false)
      }

      val channelVersionCode =
        if (variant.buildType == "preview" || (variant.buildType == "release" && isExperimentalBuild)) {
          previewVersionCode
        } else {
          output.versionCode.orNull ?: stableVersionCode
        }
      val variantSuffix = if (isPlayStoreRelease) 6 else (abiCodes[abi] ?: 0)
      output.versionCode.set(channelVersionCode * 10 + variantSuffix)
    }
  }
}

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll(
      "-opt-in=com.google.accompanist.permissions.ExperimentalPermissionsApi",
      "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
      "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
    )
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

composeCompiler {
  includeSourceInformation = false
}

room {
  schemaDirectory("$projectDir/schemas")
}

baselineProfile {
  // Ordinary release/preview builds must not require an emulator or launch instrumentation tests.
  automaticGenerationDuringBuild = false
  saveInSrc = true
}

dependencies {
  baselineProfile(project(":baselineprofile"))
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.ext.junit)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.material3.android)
  implementation(libs.google.material)
  implementation(libs.androidx.ui.tooling.preview)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.leakcanary.android)
  implementation(libs.bundles.compose.navigation3)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.core.splashscreen)
  implementation(libs.kmp.vibrate)
  implementation(libs.androidx.compose.constraintlayout)
  implementation(libs.androidx.constraintlayout)
  implementation(libs.composables.material.symbols.rounded.filled.android)
  implementation(libs.composables.material.symbols.rounded.filled.cmp)
  implementation(libs.mediasession)
  implementation(libs.androidx.documentfile)
  implementation(libs.androidx.palette)

  implementation(platform(libs.koin.bom))
  implementation(libs.bundles.koin)

  implementation(libs.seeker)
  implementation(libs.compose.prefs)
  implementation(libs.markdown.renderer.m3)

  implementation(libs.accompanist.permissions)

  implementation(libs.room.runtime)
  ksp(libs.room.compiler)
  implementation(libs.room.ktx)

  implementation(libs.kotlinx.immutable.collections)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.okhttp)
  implementation(libs.jsoup)
  implementation(libs.androidx.media3.common)
  implementation(libs.androidx.media3.exoplayer.core)
  implementation(libs.androidx.media3.exoplayer.hls)
  implementation(libs.androidx.media3.effect)
  implementation(libs.androidx.media3.transformer)
  implementation(libs.androidx.media3.ui)
  implementation(platform(libs.sora.editor.bom))
  implementation(libs.sora.editor)
  implementation(libs.sora.language.textmate)
  // implementation(libs.sora.oniguruma.native)

  coreLibraryDesugaring(libs.desugar.jdk.libs)

  implementation(libs.truetype.parser)
  implementation(libs.fsaf)
  implementation(libs.mediainfo.lib)
  implementation(libs.androidx.profileinstaller)
  implementation(libs.google.cast.framework)

  "standardImplementation"(variantOf(libs.mpvlib.standard) { artifactType("aar") })
  "playstoreImplementation"(variantOf(libs.mpvlib.standard) { artifactType("aar") })
  "noVulkanImplementation"(variantOf(libs.mpvlib.no.vulkan) { artifactType("aar") })
  "fongmiImplementation"(variantOf(libs.mpvlib.fongmi) { artifactType("aar") })

  // Network protocol libraries
  implementation(libs.smbj)
  implementation(libs.dcerpc) {
    // Reuse the existing SMBJ/crypto stack and use Android's Guava flavor.
    exclude(group = "com.hierynomus", module = "smbj")
    exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
    exclude(group = "com.google.guava", module = "guava")
  }
  implementation(libs.guava)
  implementation(libs.commons.net)
  implementation(libs.jsch)
  implementation(libs.sardine.android) {
    exclude(group = "xpp3", module = "xpp3")
  }
  implementation(libs.libarchive.android)
  implementation(libs.nanohttpd)
  implementation(libs.reorderable)
  implementation(libs.androidx.biometric)
  implementation(libs.telephoto.zoomable)
  implementation(libs.haze.blur)
  implementation(libs.sonner)
  implementation(libs.kyant.backdrop)
  implementation(libs.kyant.shapes)

  // Keep both the Java API and native payloads out of the Play Store compilation classpath.
  sideloadDistributions.forEach { flavor ->
    "${flavor}Implementation"(libs.libtorrent4j)
    "${flavor}Implementation"(libs.libtorrent4j.android.arm64)
    "${flavor}Implementation"(libs.libtorrent4j.android.arm)
    if (enableX86) {
      "${flavor}Implementation"(libs.libtorrent4j.android.x86)
      "${flavor}Implementation"(libs.libtorrent4j.android.x8664)
    }
  }
}

// ---------------- Git helpers ----------------

fun getCommitCount(): String = runCommand("git rev-list --count HEAD") ?: "0"

fun getCommitSha(): String = runCommand("git rev-parse --short HEAD") ?: "unknown"

fun runCommand(command: String): String? =
  try {
    val parts = command.split(' ')
    val process =
      ProcessBuilder(parts)
        .redirectErrorStream(true)
        .start()

    val output =
      process.inputStream
        .bufferedReader()
        .readText()
        .trim()

    process.waitFor()
    output.ifEmpty { null }
  } catch (e: Exception) {
    null
  }
