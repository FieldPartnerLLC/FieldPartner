import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// Values come from android/local.properties (never committed). setup-mac.sh writes them.
val localProps = Properties().apply {
  val f = rootDir.resolve("local.properties")
  if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(name: String, default: String) = localProps.getProperty(name)?.takeIf { it.isNotBlank() } ?: default

android {
  namespace = "com.fieldpartner.app"
  compileSdk = 36

  defaultConfig {
    applicationId = prop("fp_application_id", "com.fieldpartner.app")
    minSdk = 31 // Meta DAT requires Android 12+
    targetSdk = 36
    versionCode = prop("fp_version_code", "1").toInt()
    versionName = prop("fp_version_name", "1.0.0")
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Emulator reaches the host Mac at 10.0.2.2. A real phone needs the Mac's LAN IP.
    buildConfigField("String", "DEFAULT_API_BASE_URL", "\"${prop("fp_api_base_url", "http://10.0.2.2:8080")}\"")

    // Developer Mode uses 0 / 0. Production values come from the Wearables Developer Center.
    manifestPlaceholders["mwdat_application_id"] = prop("mwdat_application_id", "0")
    manifestPlaceholders["mwdat_client_token"] = prop("mwdat_client_token", "0")

    // Optional: build for phone CPUs only (smaller file to sideload), e.g. fp_abi_filters=arm64-v8a
    prop("fp_abi_filters", "").takeIf { it.isNotBlank() }?.let { abis -> ndk { abiFilters += abis.split(",").map { it.trim() } } }
  }

  signingConfigs {
    create("release") {
      val store = prop("fp_keystore_path", "")
      if (store.isNotEmpty()) {
        storeFile = file(store)
        storePassword = prop("fp_keystore_password", "")
        keyAlias = prop("fp_key_alias", "fieldpartner")
        keyPassword = prop("fp_key_password", "")
      }
    }
  }

  buildTypes {
    debug {
      buildConfigField("Boolean", "ALLOW_CLEARTEXT", "true")
    }
    release {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      buildConfigField("Boolean", "ALLOW_CLEARTEXT", "false")
      if (prop("fp_keystore_path", "").isNotEmpty()) signingConfig = signingConfigs.getByName("release")
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
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
  testOptions {
    unitTests.isReturnDefaultValues = true
    unitTests.isIncludeAndroidResources = true
  }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.material.icons.extended)
  implementation(libs.androidx.ui.tooling.preview)
  debugImplementation(libs.androidx.ui.tooling)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.mwdat.mockdevice)
  implementation(libs.livekit.android)
  implementation(libs.okhttp)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.robolectric)
  testImplementation(platform(libs.androidx.compose.bom))
  testImplementation(libs.androidx.ui.test.junit4)
  testImplementation(libs.androidx.test.ext.junit)
  testImplementation(libs.androidx.test.core)
  debugImplementation(libs.androidx.ui.test.manifest)
}
