/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.ByteArrayOutputStream
import java.util.Properties
import org.gradle.api.tasks.Exec
import com.mikepenz.aboutlibraries.plugin.AboutLibrariesExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

// AGP 9+ provides built-in Kotlin — do not apply org.jetbrains.kotlin.android.
// Ref: https://developer.android.com/build/migrate-to-built-in-kotlin
plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.google.services) apply false
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.hilt.application)
  alias(libs.plugins.aboutlibraries.android) apply false
  alias(libs.plugins.ksp)
}

val firebaseConfigured = layout.projectDirectory.file("google-services.json").asFile.isFile

if (firebaseConfigured) {
  pluginManager.apply("com.google.gms.google-services")
}

// Host JDK 26 compiles Java 17 bytecode for Android.
// Ref: https://developer.android.com/build/jdks#toolchain
java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(26))
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
    // Material3 tooltip/menu APIs are stable in production but still marked experimental.
    optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
  }
}

android {
  namespace = "com.google.ai.edge.gallery"
  // compileSdk 37: required by androidx.core 1.19 / activity-compose 1.13 / lifecycle 2.11.
  compileSdk = 37

  defaultConfig {
    val huggingFaceClientId =
      providers.gradleProperty("huggingFaceClientId")
        .orElse(providers.environmentVariable("HUGGING_FACE_CLIENT_ID"))
        .orElse("")
        .get()
    val huggingFaceRedirectUri =
      providers.gradleProperty("huggingFaceRedirectUri")
        .orElse(providers.environmentVariable("HUGGING_FACE_REDIRECT_URI"))
        .orElse("")
        .get()
    val huggingFaceRedirectScheme =
      providers.gradleProperty("huggingFaceRedirectScheme")
        .orElse(providers.environmentVariable("HUGGING_FACE_REDIRECT_SCHEME"))
        .orElse("com.google.ai.edge.gallery")
        .get()
    val huggingFaceAuthEndpoint =
      providers.gradleProperty("huggingFaceAuthEndpoint")
        .orElse(providers.environmentVariable("HUGGING_FACE_AUTH_ENDPOINT"))
        .orElse("")
        .get()
    val huggingFaceTokenEndpoint =
      providers.gradleProperty("huggingFaceTokenEndpoint")
        .orElse(providers.environmentVariable("HUGGING_FACE_TOKEN_ENDPOINT"))
        .orElse("")
        .get()

    applicationId = "com.google.ai.edge.gallery"
    minSdk = 31
    targetSdk = 37
    versionCode = 33
    versionName = "1.0.15"

    manifestPlaceholders["appAuthRedirectScheme"] = huggingFaceRedirectScheme
    manifestPlaceholders["applicationName"] = "com.google.ai.edge.gallery.GalleryApplication"
    manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
    manifestPlaceholders["appRoundIcon"] = "@mipmap/ic_launcher_round"

    buildConfigField(
      "String",
      "HUGGING_FACE_CLIENT_ID",
      "\"${huggingFaceClientId.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
    )
    buildConfigField(
      "String",
      "HUGGING_FACE_REDIRECT_URI",
      "\"${huggingFaceRedirectUri.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
    )
    buildConfigField(
      "String",
      "HUGGING_FACE_AUTH_ENDPOINT",
      "\"${huggingFaceAuthEndpoint.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
    )
    buildConfigField(
      "String",
      "HUGGING_FACE_TOKEN_ENDPOINT",
      "\"${huggingFaceTokenEndpoint.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
    )
    buildConfigField("boolean", "FIREBASE_CONFIGURED", firebaseConfigured.toString())

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      // Robolectric needs the merged resources/assets/manifest on the unit-test classpath to
      // inflate a real Context. Without this, any @RunWith(RobolectricTestRunner) test fails at
      // startup rather than running.
      isIncludeAndroidResources = true
    }
  }
  packaging {
    jniLibs {
      // sherpa-onnx and onnxruntime-android both ship libonnxruntime.so. Keep one packaged copy.
      pickFirsts += "**/libonnxruntime.so"
      keepDebugSymbols +=
        listOf(
          "**/libLiteRt.so",
          "**/libLiteRtClGlAccelerator.so",
          "**/libandroidx.graphics.path.so",
          "**/libdatastore_shared_counter.so",
          "**/libimage_processing_util_jni.so",
          "**/libjnidispatch.so",
          "**/liblitertlm_jni.so",
          "**/libonnxruntime.so",
          "**/libonnxruntime4j_jni.so",
          "**/libsherpa-onnx-c-api.so",
          "**/libsherpa-onnx-cxx-api.so",
          "**/libsherpa-onnx-jni.so",
          "**/libsurface_util_jni.so",
          "**/libtensorflowlite_jni_gms_client.so",
          "**/libvosk.so",
        )
    }
  }
}

val aboutLibrariesRefreshRequested =
  gradle.startParameter.taskNames.any { taskName ->
    taskName == "refreshAboutLibraries" ||
      taskName == "exportLibraryDefinitions" ||
      taskName.endsWith(":refreshAboutLibraries") ||
      taskName.endsWith(":exportLibraryDefinitions")
  }

if (aboutLibrariesRefreshRequested) {
  pluginManager.apply("com.mikepenz.aboutlibraries.plugin")
  extensions.configure<AboutLibrariesExtension>("aboutLibraries") {
    collect {
      filterVariants.add("release")
      fetchRemoteFunding.set(false)
      fetchRemoteLicense.set(false)
    }
    export {
      outputFile.set(layout.projectDirectory.file("src/main/res/raw/aboutlibraries.json"))
      variant.set("release")
    }
  }
  tasks.register("refreshAboutLibraries") {
    group = "verification"
    description = "Regenerate the third-party license metadata resource."
    dependsOn("exportLibraryDefinitions")
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.material3.adaptive)
  implementation(libs.androidx.compose.navigation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.datastore)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.security.crypto) // read-only migration reader for earlier Tink blobs
  implementation(libs.tink.android)
  implementation(libs.androidx.webkit)
  implementation(libs.litertlm)
  implementation(libs.commonmark)
  implementation(libs.richtext)
  implementation(libs.tflite)
  implementation(libs.tflite.gpu)
  implementation(libs.tflite.support)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.openid.appauth)
  implementation(libs.androidx.splashscreen)
  implementation(libs.protobuf.javalite)
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.aboutlibraries.compose.m3)
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.analytics)
  implementation(libs.firebase.messaging)
  implementation(libs.androidx.exifinterface)
  ksp(libs.hilt.android.compiler)
  // Give Hilt's KSP processor a Kotlin-2.4.0-aware metadata reader (unshaded since Dagger 2.57).
  ksp(libs.kotlin.metadata.jvm)
  testImplementation(libs.junit)
  testImplementation(libs.mockito.kotlin)
  // kotest-property is used as a LIBRARY inside plain JUnit4 @Test methods (SttFilterPropertyTest);
  // no Kotest Spec/runner classes exist, so the runner artifact is deliberately absent.
  testImplementation(libs.kotest.property)
  // Suspend-path coverage. Without this the LibreDrop connection drivers, sharing FSM loops and
  // payload streaming had no way to be exercised from a JVM unit test at all.
  testImplementation(libs.kotlinx.coroutines.test)
  // Runs Android framework classes on the JVM so Context/SharedPreferences/Resources seams get
  // coverage without an emulator. Two constraints come with it, both handled below/in-test:
  //   1. Robolectric's bundled ASM cannot read Java 26 class files ("Unsupported class file major
  //      version 70"), so the Test tasks run on a JDK 21 launcher (see `tasks.withType<Test>`).
  //   2. It ships emulated frameworks only up to SDK 36 while targetSdk is 37, so Robolectric
  //      tests carry an explicit @Config(sdk = [36]) pin.
  testImplementation(libs.robolectric)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.uiautomator)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.hilt.android.testing)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
  implementation(libs.mlkit.genai.prompt)
  implementation(libs.mcp.kotlin.sdk)
  implementation(libs.ktor.client.android)
  implementation(libs.ktor.client.core)
  implementation(files("libs/sherpa-onnx-v1.13.2.aar"))
  // ONNX Runtime (Microsoft) — runs the OpenVoice converter + ref_enc ONNX graphs at EXACT
  // utterance length (dynamic time dim), which litert-torch cannot export. Validated 99 dB vs
  // PyTorch; exact length keeps the dilated WaveNet output crisp (fixed-length TFLite smeared it).
  implementation(libs.onnxruntime.android)
  // Vosk (Kaldi): the ONLY on-device engine with TRUE streaming partials for the app's non-CJK
  // languages (Spanish/French/German/Russian/Hindi/...), which sherpa-onnx has no streaming model
  // for. Ships its own libvosk.so (no libonnxruntime.so clash). Used for multilingual live captions;
  // the sherpa zipformer transducer stays the English caption engine.
  implementation(libs.vosk.android)
  // Google Nearby Connections: maintained, multi-medium (BLE + Bluetooth Classic + Wi-Fi) P2P
  // transport for the multi-device conversation mesh (see BleConversationManager).
  implementation(libs.play.services.nearby)
  implementation(libs.commons.compress)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.zxing.core)
}

// Verification gates (verifyReleaseReady, testDebugUnitTestStrict) live in their own script so
// this file stays focused on Android/Kotlin configuration.
//
// The script lives under ../gradle/ rather than in this module directory on purpose: Android
// Lint's `checkBuildScripts` pass walks every *.gradle.kts inside the module and crashes in the
// Kotlin analysis API when it tries to resolve an applied script that has no compilation
// classpath of its own. Keeping it out of the module directory sidesteps that entirely.
apply(from = "${rootProject.projectDir}/gradle/verification.gradle.kts")

// Smoke subset: only SmokeE2eTest — not the full connectedDebugAndroidTest matrix (language
// matrix, live mic, OpenVoice, etc. require models + minutes of runtime).
tasks.register<Exec>("smokeE2e") {
  group = "verification"
  description = "Install and run SmokeE2eTest on a connected device or emulator."
  dependsOn("assembleDebug", "assembleDebugAndroidTest", "installDebug", "installDebugAndroidTest")
  val localPropertiesFile = rootProject.file("local.properties")
  val appId = android.defaultConfig.applicationId
  // `adb shell am instrument` returns shell exit 0 even when tests FAIL or the test process
  // CRASHES — the real result is only in the instrumentation text output. Capture it and assert,
  // otherwise this gate is permanently green and masks failures (a hung/crashed test "passes").
  val captured = ByteArrayOutputStream()
  standardOutput = captured
  errorOutput = captured
  isIgnoreExitValue = true
  doFirst {
    val props = Properties()
    check(localPropertiesFile.exists()) {
      "local.properties missing — set sdk.dir to run smokeE2e"
    }
    localPropertiesFile.inputStream().use { props.load(it) }
    val sdkDir =
      props.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: error("sdk.dir not found in local.properties and ANDROID_HOME unset")
    commandLine(
      "$sdkDir/platform-tools/adb",
      "shell",
      "am",
      "instrument",
      "-w",
      "-e",
      "class",
      // Real on-device coverage, both deterministic and model-free:
      //  - SkillWebViewBridgeE2eTest: skill ES-module loading + native storage bridge round-trip +
      //    vendored-font + a11y DOM + realtime re-render.
      //  - UiAutomatorSmokeTest: app launch + home/drawer/models/settings navigation, driven by
      //    UiAutomator (accessibility-tree polling) so it never calls Compose waitForIdle() — which
      //    hangs on this app. It replaced the old createAndroidComposeRule SmokeE2eTest that hung at
      //    launch and was historically masked green by the am-instrument exit-0 bug (now fixed).
      "com.google.ai.edge.gallery.SkillWebViewBridgeE2eTest,com.google.ai.edge.gallery.UiAutomatorSmokeTest",
      "$appId.test/androidx.test.runner.AndroidJUnitRunner",
    )
  }
  doLast {
    val out = captured.toString()
    logger.lifecycle(out)
    val failureMarkers =
      listOf("FAILURES!!!", "Process crashed", "INSTRUMENTATION_STATUS_CODE: -1", "shortMsg=", "Test run failed")
    val hasFailure = failureMarkers.firstOrNull { out.contains(it) } != null
    val sawSuccess = Regex("OK \\(\\d+ test").containsMatchIn(out)
    if (hasFailure || !sawSuccess) {
      throw GradleException(
        "smokeE2e FAILED — am instrument masks this in its exit code. Instrumentation output:\n$out"
      )
    }
  }
}

protobuf {
  // protoc must match the protobuf-javalite runtime — both read the same catalog version.
  protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobufJavaLite.get()}" }
  generateProtoTasks { all().configureEach { builtins { create("java") { option("lite") } } } }
}


// Sync skills/ into the app bundle as a pre-build step.
// skills/ is the single source of truth; the Android copy is gitignored.
// Layout must stay FLAT (assets/skills/<id>/SKILL.md) — SkillManagerViewModel
// lists assets.list("skills") and reads skills/<dir>/SKILL.md directly.
// Ships built-in + featured skills + the shared/ runtime modules. Featured skills are flattened
// into the same assets/skills/ dir so they ship in-APK and are available offline (they import the
// same shared/ runtime via ../../shared, which resolves in the flat layout). _vendor/ is a 32 MB
// package mirror whose needed files are already vendored per-skill, so it is NOT copied.
// NOTE: the shared dir must NOT be underscore-prefixed — aapt drops `_*` assets
// from the APK, which silently breaks every skill's `import '../../shared/*'`.
// Sync (not Copy): the destination is generated output, so stale files from removed/renamed
// skills must be pruned, or deleted skills would silently keep shipping in the APK.
tasks.register<Sync>("syncSkills") {
  group = "build"
  description = "Sync skills/built-in + skills/featured + skills/shared into Android assets before build."
  val skillsDir = file("../../../skills")
  into(file("src/main/assets/skills"))
  from(skillsDir.resolve("built-in"))
  from(skillsDir.resolve("featured"))
  from(skillsDir.resolve("shared")) { into("shared") }
}

tasks.named("preBuild") {
  dependsOn("syncSkills")
}

// Test-task JVM configuration lives in gradle/verification.gradle.kts alongside the other
// verification wiring.
