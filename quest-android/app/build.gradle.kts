plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  // Meta Spatial Gradle plugin: used only to compile custom GLSL shaders (src/shaders → SPIR-V in assets, with the
  // NDK's glslc). No Spatial Editor scenes are configured, so no Spatial Editor install is needed.
  alias(libs.plugins.meta.spatial)
}

android {
  namespace = "com.agj.android"
  compileSdk = 34
  // glslc for the shaders (Meta's samples pin the same NDK)
  ndkVersion = "27.0.12077973"

  defaultConfig {
    applicationId = "com.agj.android"
    // Horizon OS is Android 14 (API 34); Meta's passthrough-camera sample uses minSdk 34 too.
    minSdk = 34
    //noinspection ExpiredTargetSdkVersion,OldTargetApi
    targetSdk = 34
    versionCode = 1
    versionName = "0.1.0"
    // Quest 3 is arm64 only; drops OpenCV's x86/armv7 copies (~110 MB).
    ndk { abiFilters += "arm64-v8a" }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging { resources.excludes.add("META-INF/LICENSE*") }
  lint {
    abortOnError = false
    checkReleaseBuilds = false
  }
  testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
  implementation(libs.meta.spatial.sdk.base)
  implementation(libs.meta.spatial.sdk.vr)
  implementation(libs.meta.spatial.sdk.toolkit)
  implementation(libs.meta.spatial.sdk.compose)
  implementation(libs.meta.spatial.sdk.isdk)
  implementation(libs.meta.spatial.sdk.mruk)
  implementation(libs.meta.spatial.sdk.animation)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.material3)

  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.okhttp)
  implementation(libs.opencv)

  testImplementation(libs.junit)
  testImplementation(libs.okhttp.mockwebserver)
  // Desktop OpenCV Java bindings (+ macOS/Linux/Windows natives) so MarkerDetector runs in JVM tests on the
  // synthetic replay set. The Android AAR's org.opencv classes are removed from the unit-test runtime below so the
  // two copies do not clash.
  testImplementation(libs.opencv.desktop)
}

configurations
    .matching { it.name.endsWith("UnitTestRuntimeClasspath") }
    .configureEach { exclude(group = "org.opencv", module = "opencv") }

spatial {
  allowUsageDataCollection.set(false)
  shaders { sources.add(project.layout.projectDirectory.dir("src/shaders")) }
}
