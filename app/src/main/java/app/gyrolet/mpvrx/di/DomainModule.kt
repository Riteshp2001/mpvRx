/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.di

import app.gyrolet.mpvrx.domain.anime4k.Anime4KManager
import app.gyrolet.mpvrx.domain.hdr.HdrToysManager
import app.gyrolet.mpvrx.network.AndroidCookieJar
import app.gyrolet.mpvrx.preferences.AiPreferences
import app.gyrolet.mpvrx.repository.IntroDbRepository
import app.gyrolet.mpvrx.repository.ai.AiClient
import app.gyrolet.mpvrx.repository.ai.AiService
import app.gyrolet.mpvrx.repository.ai.AnthropicClient
import app.gyrolet.mpvrx.repository.ai.GroqClient
import app.gyrolet.mpvrx.repository.ai.GroqSpeechClient
import app.gyrolet.mpvrx.repository.ai.LlamaCppInference
import app.gyrolet.mpvrx.repository.ai.LocalAiClient
import app.gyrolet.mpvrx.repository.ai.ModelDownloadManager
import app.gyrolet.mpvrx.repository.ai.OpenAiClient
import app.gyrolet.mpvrx.repository.ai.OpenCodeClient
import app.gyrolet.mpvrx.repository.ai.OpenRouterClient
import app.gyrolet.mpvrx.repository.ai.OpenRouterSpeechClient
import app.gyrolet.mpvrx.repository.ai.RealtimeSubtitleService
import app.gyrolet.mpvrx.repository.ai.SubtitleGenerationService
import app.gyrolet.mpvrx.repository.ai.TogetherClient
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitleFileStore
import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitleOrchestrator
import app.gyrolet.mpvrx.repository.subtitlehub.MpvRxSubtitleHubRepository
import app.gyrolet.mpvrx.repository.wyzie.WyzieSearchRepository
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

val domainModule =
  module {
    single {
      OkHttpClient
        .Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .cookieJar(AndroidCookieJar())
        .build()
    }
    single { Anime4KManager(androidContext()) }
    single { HdrToysManager(androidContext()) }
    single { OnlineSubtitleFileStore(androidContext(), get()) }
    single { WyzieSearchRepository(androidContext(), get(), get(), get(), get()) }
    single { MpvRxSubtitleHubRepository(get(), get(), get(), get()) }
    single { OnlineSubtitleOrchestrator(get<WyzieSearchRepository>(), get<MpvRxSubtitleHubRepository>()) }
    single { IntroDbRepository(get(), get()) }
    single { OpenCodeClient(get(), get()) }
    single { GroqClient(get(), get()) }
    single { OpenAiClient(get(), get()) }
    single { AnthropicClient(get(), get()) }
    single { OpenRouterClient(get(), get()) }
    single { TogetherClient(get(), get()) }
    single { GroqSpeechClient(get(), get()) }
    single { OpenRouterSpeechClient(get(), get()) }
    single<app.gyrolet.mpvrx.repository.ai.LlmInference> {
      app.gyrolet.mpvrx.repository.ai
        .LlamaCppInference()
    }
    single<AiClient>(named("opencode")) { OpenCodeClient(get(), get()) }
    single<AiClient>(named("groq")) { GroqClient(get(), get()) }
    single<AiClient>(named("openai")) { OpenAiClient(get(), get()) }
    single<AiClient>(named("anthropic")) { AnthropicClient(get(), get()) }
    single<AiClient>(named("openrouter")) { OpenRouterClient(get(), get()) }
    single<AiClient>(named("together")) { TogetherClient(get(), get()) }
    single { LocalAiClient(get()) }
    single { ModelDownloadManager(get()) }
    single { SubtitleGenerationService(androidContext(), get(), get(), get(), get(), get()) }
    single { RealtimeSubtitleService(androidContext(), get(), get(), get(), get(), get()) }
    single {
      AiService(
        androidContext(),
        get<AiPreferences>(),
        get<AiClient>(named("opencode")),
        get<AiClient>(named("groq")),
        get<AiClient>(named("openai")),
        get<AiClient>(named("anthropic")),
        get<AiClient>(named("openrouter")),
        get<AiClient>(named("together")),
        get<LocalAiClient>(),
        get<ModelDownloadManager>(),
        get<Json>(),
      )
    }
    single {
      app.gyrolet.mpvrx.domain.syncplay
        .SyncplayManager(androidContext())
    }
  }
