/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository

import android.os.SystemClock
import android.util.Patterns
import app.gyrolet.mpvrx.network.awaitResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

internal data class GitHubContributor(
  val displayName: String,
  val avatarUrl: String?,
  val profileUrl: String?,
  val contributions: Int,
)

internal class GitHubContributorsRepository(
  private val client: OkHttpClient,
  private val json: Json,
) {
  @Volatile
  private var cache: ContributorCache? = null

  suspend fun contributors(forceRefresh: Boolean = false): Result<List<GitHubContributor>> =
    withContext(Dispatchers.IO) {
      if (!forceRefresh) freshCache()?.let { return@withContext Result.success(it) }

      try {
        val contributors =
          fetchContributorResponses()
            .asSequence()
            .filterNot { it.type.equals("Bot", ignoreCase = true) }
            .mapNotNull { contributor ->
              val displayName =
                contributor.login?.trim()?.takeIf(String::isNotBlank)
                  ?: contributor.name
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.takeUnless { Patterns.EMAIL_ADDRESS.matcher(it).find() }
                  ?: return@mapNotNull null
              GitHubContributor(
                displayName = displayName,
                avatarUrl = contributor.avatarUrl?.takeIf { it.startsWith("https://") },
                profileUrl = contributor.profileUrl?.takeIf { it.startsWith("https://github.com/") },
                contributions = contributor.contributions.coerceAtLeast(0),
              )
            }.distinctBy { it.displayName.lowercase(Locale.ROOT) }
            .sortedWith(
              compareByDescending<GitHubContributor> { it.contributions }
                .thenBy { it.displayName.lowercase(Locale.ROOT) },
            )
            .toList()
        synchronized(this@GitHubContributorsRepository) {
          cache = ContributorCache(contributors, SystemClock.elapsedRealtime())
        }
        Result.success(contributors)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: Exception) {
        freshCache(ignoreAge = true)?.let { return@withContext Result.success(it) }
        Result.failure(error)
      }
    }

  private suspend fun fetchContributorResponses(): List<GitHubContributorResponse> {
    val contributors = mutableListOf<GitHubContributorResponse>()
    var pageNumber = 1
    var hasNextPage: Boolean
    do {
      val page = fetchContributorPage(pageNumber)
      contributors += page.contributors
      hasNextPage = page.hasNextPage && pageNumber < MAX_CONTRIBUTOR_PAGES
      pageNumber++
    } while (hasNextPage)
    return contributors
  }

  private suspend fun fetchContributorPage(pageNumber: Int): ContributorPage {
    val url =
      API_URL
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("per_page", CONTRIBUTORS_PER_PAGE.toString())
        .addQueryParameter("page", pageNumber.toString())
        .addQueryParameter("anon", "1")
        .build()
    val request =
      Request
        .Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "mpvRx-Android")
        .build()

    return client.newCall(request).awaitResponse().use { response ->
      if (!response.isSuccessful) {
        throw IllegalStateException("GitHub contributors request failed with HTTP ${response.code}")
      }
      val pageContributors =
        json.decodeFromString(ListSerializer(GitHubContributorResponse.serializer()), response.body.string())
      val linkHeader = response.header("Link")
      ContributorPage(
        contributors = pageContributors,
        hasNextPage =
          linkHeader?.contains("rel=\"next\"") == true ||
            (linkHeader == null && pageContributors.size == CONTRIBUTORS_PER_PAGE),
      )
    }
  }

  private fun freshCache(ignoreAge: Boolean = false): List<GitHubContributor>? =
    synchronized(this) {
      cache?.takeIf { ignoreAge || SystemClock.elapsedRealtime() - it.cachedAtMs < CACHE_TTL_MS }?.contributors
    }

  private data class ContributorCache(
    val contributors: List<GitHubContributor>,
    val cachedAtMs: Long,
  )

  private data class ContributorPage(
    val contributors: List<GitHubContributorResponse>,
    val hasNextPage: Boolean,
  )

  @Serializable
  private data class GitHubContributorResponse(
    val login: String? = null,
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("html_url") val profileUrl: String? = null,
    val contributions: Int = 0,
    val type: String? = null,
  )

  private companion object {
    const val API_URL = "https://api.github.com/repos/Riteshp2001/mpvRx/contributors"
    const val CONTRIBUTORS_PER_PAGE = 100
    const val MAX_CONTRIBUTOR_PAGES = 10
    const val CACHE_TTL_MS = 30L * 60L * 1_000L
  }
}
