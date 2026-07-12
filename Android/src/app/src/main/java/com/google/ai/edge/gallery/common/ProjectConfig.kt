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

package com.google.ai.edge.gallery.common

import androidx.core.net.toUri
import com.google.ai.edge.gallery.BuildConfig
import net.openid.appauth.AuthorizationServiceConfiguration

object ProjectConfig {
  /** Custom URI scheme registered with `appAuthRedirectScheme` in build.gradle.kts.
   *  Used for internal deep links and OAuth redirects — single source of truth. */
  const val deepLinkScheme = "com.google.ai.edge.gallery"

  const val deepLinkModelPath = "$deepLinkScheme://model/"

  const val deepLinkGlobalModelManager = "$deepLinkScheme://global_model_manager"

  // Hugging Face OAuth client ID and redirect URI — injected from gradle.properties via
  // BuildConfig so credentials never live as literals in source. Defaults are placeholders
  // that compile but fail at runtime; set real values via `bao.hfOauth*` gradle properties
  // (in a gitignored local gradle.properties or CI secret) before a source build that needs
  // model downloads. See DEVELOPMENT.md for HF OAuth app registration steps.
  val clientId: String = BuildConfig.HF_OAUTH_CLIENT_ID
  val redirectUri: String = BuildConfig.HF_OAUTH_REDIRECT_URI
  val redirectScheme: String = BuildConfig.HF_OAUTH_REDIRECT_SCHEME

  // OAuth 2.0 Endpoints (Authorization + Token Exchange)
  private const val authEndpoint = "https://huggingface.co/oauth/authorize"
  private const val tokenEndpoint = "https://huggingface.co/oauth/token"

  // OAuth service configuration (AppAuth library requires this)
  val authServiceConfig =
    AuthorizationServiceConfiguration(
      authEndpoint.toUri(), // Authorization endpoint
      tokenEndpoint.toUri(), // Token exchange endpoint
    )

  /** Application version name from build config. Single source of truth for version display. */
  val versionName: String = BuildConfig.VERSION_NAME

  /** Application version code from build config. */
  val versionCode: Int = BuildConfig.VERSION_CODE

  /** True when running on Android runtime (vs plain JVM unit tests). */
  val isAndroidRuntime: Boolean =
    (System.getProperty("java.runtime.name", "") ?: "").contains("Android", ignoreCase = true)
}
