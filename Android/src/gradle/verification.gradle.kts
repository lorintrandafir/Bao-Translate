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

// Verification gates for :app, split out of build.gradle.kts to keep that file focused on the
// Android/Kotlin configuration. Applied from build.gradle.kts via `apply(from = ...)`.
//
// Everything here is plain task wiring — no `android {}` or version-catalog accessors — which is
// exactly why this block is the one that extracts cleanly into an applied script.

import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

tasks.withType<Test>().configureEach {
  jvmArgs("-Dnet.bytebuddy.experimental=true")
  // Unit tests execute on JDK 21 even though compilation uses the JDK 26 toolchain. Robolectric's
  // bundled ASM rejects Java 26 class files outright ("Unsupported class file major version 70"),
  // which fails every Robolectric test at instrumentation time. Production bytecode targets Java
  // 17, so running the suite on 21 is fully compatible and changes nothing about what is tested.
  // `project.extensions`, not the bare `extensions` accessor: inside an applied script the latter
  // resolves to the script object's own (empty) extension container.
  javaLauncher.set(
    project.extensions
      .getByType(JavaToolchainService::class.java)
      .launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
  )
}

// CI verification gate. `:app:verifyReleaseReady` runs every guard the release
// pipeline needs in a single invocation: typecheck, unit tests, Android Lint,
// and the full debug APK assembly. Wired here per OSF-008 so a build rot
// regression (cycles 1-5) cannot recur without a red CI run.
tasks.register("verifyReleaseReady") {
  group = "verification"
  description = "Run typecheck + tests + lint + debug APK assembly in one gate."
  dependsOn(
    "compileDebugKotlin",
    "compileDebugAndroidTestKotlin",
    "testDebugUnitTest",
    "lintDebug",
    "assembleDebug",
    "assembleDebugAndroidTest",
  )
}

// Strict subset: runs only tests marked @Category(Strict.class). Promoted to the release
// gate so newly hardened tests exercise production paths with no skips, optimistic markers, or
// time-padding Thread.sleeps. Filters via the JUnit 4
// categories mechanism — the @Category marker on each test class is what gates inclusion.
tasks.register<Test>("testDebugUnitTestStrict") {
  group = "verification"
  description = "Run the @Category(Strict) subset of unit tests. Gating for release."
  // Reuse the EXACT compiled classes + runtime classpath of the full debug unit-test task, so this
  // runs the same bytecode — just filtered. Lazy files{} resolve at execution (order-independent);
  // dependsOn guarantees the test classes are compiled first.
  val full = tasks.named<Test>("testDebugUnitTest")
  // Lazy files{} carry the producing-task dependencies (compile + resources), so this builds the
  // test classes WITHOUT running the full suite — the strict gate must stand on its own.
  testClassesDirs = files({ full.get().testClassesDirs })
  classpath = files({ full.get().classpath })
  dependsOn("compileDebugUnitTestKotlin", "processDebugUnitTestJavaRes")
  // Real JUnit 4 category filtering: only classes/methods tagged @Category(Strict::class) run.
  useJUnit { includeCategories("com.google.ai.edge.gallery.testkit.Strict") }
}

tasks.named("verifyReleaseReady") {
  // Strict subset is gating; runs as part of the release gate alongside the full unit suite.
  dependsOn("testDebugUnitTestStrict")
}
