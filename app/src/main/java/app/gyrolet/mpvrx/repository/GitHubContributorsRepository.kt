/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository

import android.content.Context
import android.util.Patterns
import app.gyrolet.mpvrx.network.awaitResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

@Serializable
internal data class GitHubContributor(
  val displayName: String,
  val avatarUrl: String?,
  val profileUrl: String?,
  val contributions: Int,
)

@Serializable
internal data class GitHubCommunityMember(
  val login: String,
  val avatarUrl: String?,
  val profileUrl: String,
  val issuesReported: Int = 0,
  val feedbackComments: Int = 0,
)

internal class GitHubContributorsRepository(
  private val client: OkHttpClient,
  private val json: Json,
  context: Context,
) {
  private val preferences = context.applicationContext.getSharedPreferences("hall_of_fame", Context.MODE_PRIVATE)
  private val cacheMutex = Mutex()

  suspend fun contributors(forceRefresh: Boolean = false): Result<List<GitHubContributor>> =
    cachedCredits("contributors", GitHubContributor.serializer(), forceRefresh) {
      fetchPages("contributors", GitHubContributorResponse.serializer(), mapOf("anon" to "1"))
        .filterNot {
          it.type.equals("Bot", ignoreCase = true) ||
            it.login?.endsWith("[bot]", ignoreCase = true) == true ||
            it.name?.endsWith("[bot]", ignoreCase = true) == true
        }.mapNotNull { contributor ->
          val login = contributor.login?.trim()?.takeIf(String::isNotBlank)
          val name =
            login ?: contributor.name
              ?.trim()
              ?.takeIf(String::isNotBlank)
              ?.takeUnless { Patterns.EMAIL_ADDRESS.matcher(it).find() }
          name?.let {
            GitHubContributor(
              displayName = it,
              avatarUrl = avatarThumbnail(contributor.avatarUrl),
              profileUrl = login?.let { user -> "https://github.com/$user" },
              contributions = contributor.contributions.coerceAtLeast(0),
            )
          }
        }.groupBy { it.profileUrl ?: "anonymous:${it.displayName}" }
        .values
        .map { entries -> entries.first().copy(contributions = entries.sumOf { it.contributions }) }
        .sortedWith(contributorOrder)
    }

  suspend fun activeContributors(forceRefresh: Boolean = false): Result<List<GitHubContributor>> =
    cachedCredits("active_contributors", GitHubContributor.serializer(), forceRefresh) {
      val since = Instant.now().minus(90, ChronoUnit.DAYS).toString()
      fetchPages("commits", GitHubCommitResponse.serializer(), mapOf("since" to since))
        .distinctBy { it.sha }
        .mapNotNull { it.author?.takeIf(GitHubUserResponse::isPerson) }
        .groupBy { it.login.lowercase(Locale.ROOT) }
        .values
        .map { commits ->
          val author = commits.first()
          GitHubContributor(
            displayName = author.login,
            avatarUrl = avatarThumbnail(author.avatarUrl),
            profileUrl = "https://github.com/${author.login}",
            contributions = commits.size,
          )
        }.sortedWith(contributorOrder)
    }

  suspend fun communityMembers(forceRefresh: Boolean = false): Result<List<GitHubCommunityMember>> =
    cachedCredits("community", GitHubCommunityMember.serializer(), forceRefresh) {
      val issues =
        fetchPages(
          "issues",
          GitHubIssueResponse.serializer(),
          mapOf("state" to "all", "sort" to "created", "direction" to "asc"),
        ).filter { it.pullRequest == null }.distinctBy { it.number }
      val issueNumbers = issues.mapTo(mutableSetOf()) { it.number }
      val members = linkedMapOf<String, GitHubCommunityMember>()

      fun credit(
        user: GitHubUserResponse?,
        issue: Boolean,
      ) {
        if (user == null || !user.isPerson()) return
        val key = user.login.lowercase(Locale.ROOT)
        val member =
          members[key] ?: GitHubCommunityMember(
            login = user.login,
            avatarUrl = avatarThumbnail(user.avatarUrl),
            profileUrl = "https://github.com/${user.login}",
          )
        members[key] =
          if (issue) {
            member.copy(issuesReported = member.issuesReported + 1)
          } else {
            member.copy(feedbackComments = member.feedbackComments + 1)
          }
      }

      issues.forEach { credit(it.user, issue = true) }
      fetchPages("issues/comments", GitHubCommentResponse.serializer())
        .distinctBy { it.id }
        .filter { it.issueUrl.substringAfterLast('/').toIntOrNull() in issueNumbers }
        .forEach { credit(it.user, issue = false) }
      members.values.sortedWith(
        compareByDescending<GitHubCommunityMember> { it.issuesReported }
          .thenByDescending { it.feedbackComments }
          .thenBy { it.login.lowercase(Locale.ROOT) },
      )
    }

  private fun avatarThumbnail(url: String?): String? =
    url
      ?.toHttpUrlOrNull()
      ?.takeIf { it.isHttps && it.host == "avatars.githubusercontent.com" }
      ?.newBuilder()
      ?.setQueryParameter("s", "160")
      ?.build()
      ?.toString()

  private suspend fun <T> cachedCredits(
    key: String,
    serializer: KSerializer<T>,
    forceRefresh: Boolean,
    fetch: suspend () -> List<T>,
  ): Result<List<T>> =
    withContext(Dispatchers.IO) {
      cacheMutex.withLock {
        val listSerializer = ListSerializer(serializer)
        val cached =
          preferences.getString(key, null)?.let { encoded ->
            runCatching { json.decodeFromString(listSerializer, encoded) }.getOrNull()
          }
        val age = System.currentTimeMillis() - preferences.getLong("${key}_updated", 0L)
        if (!forceRefresh && cached != null && age in 0L until CACHE_TTL_MS) {
          return@withLock Result.success(cached)
        }
        try {
          val entries = fetch()
          preferences
            .edit()
            .putString(key, json.encodeToString(listSerializer, entries))
            .putLong("${key}_updated", System.currentTimeMillis())
            .apply()
          Result.success(entries)
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (error: Exception) {
          if (cached != null) Result.success(cached) else Result.failure(error)
        }
      }
    }

  private suspend fun <T> fetchPages(
    endpoint: String,
    serializer: KSerializer<T>,
    parameters: Map<String, String> = emptyMap(),
  ): List<T> {
    val entries = mutableListOf<T>()
    var pageNumber = 1
    do {
      val url =
        "$API_URL/$endpoint"
          .toHttpUrl()
          .newBuilder()
          .addQueryParameter("per_page", PAGE_SIZE.toString())
          .addQueryParameter("page", pageNumber.toString())
      parameters.forEach { (name, value) -> url.addQueryParameter(name, value) }
      val request =
        Request
          .Builder()
          .url(url.build())
          .header("Accept", "application/vnd.github+json")
          .header("X-GitHub-Api-Version", "2022-11-28")
          .header("User-Agent", "mpvRx-Android")
          .build()
      val hasNextPage =
        client.newCall(request).awaitResponse().use { response ->
          check(response.isSuccessful && response.code != 202) {
            "GitHub credits request failed with HTTP ${response.code}"
          }
          val pageEntries = json.decodeFromString(ListSerializer(serializer), response.body.string())
          entries += pageEntries
          val linkHeader = response.header("Link")
          pageEntries.isNotEmpty() &&
            (
              linkHeader?.contains("rel=\"next\"") == true ||
                (linkHeader == null && pageEntries.size == PAGE_SIZE)
            )
        }
      pageNumber++
    } while (hasNextPage)
    return entries
  }

  @Serializable
  private data class GitHubContributorResponse(
    val login: String? = null,
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val contributions: Int = 0,
    val type: String? = null,
  )

  @Serializable
  private data class GitHubUserResponse(
    val login: String,
    val type: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
  ) {
    fun isPerson(): Boolean = login.isNotBlank() && !type.equals("Bot", true) && !login.endsWith("[bot]")
  }

  @Serializable
  private data class GitHubCommitResponse(
    val sha: String,
    val author: GitHubUserResponse? = null,
  )

  @Serializable
  private data class GitHubIssueResponse(
    val number: Int,
    val user: GitHubUserResponse? = null,
    @SerialName("pull_request") val pullRequest: kotlinx.serialization.json.JsonObject? = null,
  )

  @Serializable
  private data class GitHubCommentResponse(
    val id: Long,
    val user: GitHubUserResponse? = null,
    @SerialName("issue_url") val issueUrl: String,
  )

  private companion object {
    const val API_URL = "https://api.github.com/repos/Riteshp2001/mpvRx"
    const val PAGE_SIZE = 100
    const val CACHE_TTL_MS = 24L * 60L * 60L * 1_000L
    val contributorOrder =
      compareByDescending<GitHubContributor> { it.contributions }
        .thenBy { it.displayName.lowercase(Locale.ROOT) }
  }
}
