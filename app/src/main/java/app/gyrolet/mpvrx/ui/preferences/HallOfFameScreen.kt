package app.gyrolet.mpvrx.ui.preferences

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.repository.GitHubCommunityMember
import app.gyrolet.mpvrx.repository.GitHubContributor
import app.gyrolet.mpvrx.repository.GitHubContributorsRepository
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.connectedCornerRadii
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.LocalShowSettingsBackArrow
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import java.text.NumberFormat
import java.util.Locale

/** Contributor avatars are re-downloaded at most once every 72 hours. */
private const val AVATAR_CACHE_TTL_MS = 72L * 60L * 60L * 1000L

@Serializable
object HallOfFameScreen : ScrollableSettingsScreen {
  @Composable
  override fun Content() {
    val repository = koinInject<GitHubContributorsRepository>()
    val backstack = LocalBackStack.current
    val uriHandler = LocalUriHandler.current
    val githubRepoUrl = stringResource(R.string.github_repo_url).trimEnd('/')
    var refreshRequest by remember { mutableIntStateOf(0) }
    var contributors by remember { mutableStateOf(CreditsState<GitHubContributor>()) }
    var active by remember { mutableStateOf(CreditsState<GitHubContributor>()) }
    var community by remember { mutableStateOf(CreditsState<GitHubCommunityMember>()) }

    LaunchedEffect(refreshRequest) {
      contributors = contributors.copy(loading = true, failed = false)
      active = active.copy(loading = true, failed = false)
      community = community.copy(loading = true, failed = false)
      launch {
        repository
          .contributors(forceRefresh = refreshRequest > 0)
          .onSuccess { contributors = CreditsState(entries = it, loading = false) }
          .onFailure { contributors = contributors.copy(loading = false, failed = true) }
      }
      launch {
        repository
          .activeContributors(forceRefresh = refreshRequest > 0)
          .onSuccess { entries ->
            active =
              CreditsState(
                entries = entries.filterNot { it.displayName.lowercase(Locale.ROOT) in leadLogins },
                loading = false,
              )
          }.onFailure { active = active.copy(loading = false, failed = true) }
      }
      launch {
        repository
          .communityMembers(forceRefresh = refreshRequest > 0)
          .onSuccess { entries ->
            community =
              CreditsState(
                entries = entries.filterNot { it.login.lowercase(Locale.ROOT) in leadLogins },
                loading = false,
              )
          }.onFailure { community = community.copy(loading = false, failed = true) }
      }
    }

    val loading = contributors.loading || active.loading || community.loading
    val colors = MaterialTheme.colorScheme
    val remainingActive =
      active.copy(
        entries = active.entries.filterNot { it.displayName.lowercase(Locale.ROOT) in featuredLogins },
      )
    val topReporters = community.entries.filter { it.issuesReported > 0 }.take(3)

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var showAll by rememberSaveable { mutableStateOf(false) }
    // Grid index of the "All contributors" header, recorded while the grid content is built so
    // "View All" can scroll straight to it.
    val allHeaderIndex = remember { intArrayOf(0) }
    val showBack = LocalShowSettingsBackArrow.current

    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .background(
            Brush.verticalGradient(
              listOf(colors.background, colors.primaryContainer.copy(alpha = 0.22f), colors.background),
            ),
          ),
    ) {
      Scaffold(containerColor = Color.Transparent) { paddingValues ->
        BoxWithConstraints(
          modifier = Modifier.fillMaxSize().padding(paddingValues),
          contentAlignment = Alignment.TopCenter,
        ) {
          val gridColumns =
            (
              (maxWidth.coerceAtMost(960.dp) - 32.dp + AppConnectedShapeTokens.spacing) /
                (148.dp + AppConnectedShapeTokens.spacing)
            ).toInt().coerceAtLeast(1)
          LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(148.dp),
            modifier = Modifier.widthIn(max = 960.dp).fillMaxSize(),
            contentPadding =
              settingsContentPadding(
                PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
              ),
            horizontalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
            verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
          ) {
            var index = 0

            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
              Box(Modifier.settingsSearchTarget(R.string.pref_hall_of_fame_title)) {
                HallOfFameHeader(
                  showBack = showBack,
                  loading = loading,
                  onBack = { backstack.popSafely() },
                  onRefresh = { refreshRequest++ },
                )
              }
            }
            index++

            item(key = "creators", span = { GridItemSpan(maxLineSpan) }) {
              Column(verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing)) {
                HallOfFameLeadCard(
                  name = "MarlboroAdvance",
                  handle = "marlboro-advance",
                  tag = stringResource(R.string.hall_of_fame_original_developer),
                  avatarUrl = "https://avatars.githubusercontent.com/u/227117361?s=256",
                  profileUrl = "https://github.com/marlboro-advance",
                  brush =
                    Brush.linearGradient(
                      listOf(colors.primaryContainer, colors.secondaryContainer.copy(alpha = 0.85f)),
                    ),
                  contentColor = colors.onPrimaryContainer,
                  shape = rememberConnectedShape(0, 2, ConnectedLayout.Vertical),
                )
                HallOfFameLeadCard(
                  name = "Ritesh Pandit",
                  handle = "Riteshp2001",
                  tag = stringResource(R.string.hall_of_fame_maintainer),
                  avatarUrl = "https://avatars.githubusercontent.com/u/87899750?s=256",
                  profileUrl = "https://github.com/Riteshp2001",
                  brush =
                    Brush.linearGradient(
                      listOf(colors.secondaryContainer.copy(alpha = 0.9f), colors.surfaceContainerHigh),
                    ),
                  contentColor = colors.onSecondaryContainer,
                  shape = rememberConnectedShape(1, 2, ConnectedLayout.Vertical),
                )
              }
            }
            index++

            item(key = "featured:header", span = { GridItemSpan(maxLineSpan) }) {
              SectionHeader(
                icon = { Icon(Icons.RoundedFilled.Star, null, Modifier.size(24.dp), tint = colors.primary) },
                title = stringResource(R.string.hall_of_fame_featured_contributors),
                subtitle = stringResource(R.string.hall_of_fame_featured_subtitle),
                trailing = {
                  ViewAllPill(
                    onClick = {
                      showAll = true
                      scope.launch { gridState.animateScrollToItem(allHeaderIndex[0]) }
                    },
                  )
                },
              )
            }
            index++

            item(key = "featured:profiles", span = { GridItemSpan(maxLineSpan) }) {
              HallOfFameTopThree(featuredContributors) { contributor, tileModifier, shape ->
                HallOfFameSpotlightCard(
                  name = contributor.name,
                  subtitle = "@${contributor.login}",
                  badge = stringResource(R.string.hall_of_fame_contributor_badge),
                  avatarUrl = "https://avatars.githubusercontent.com/u/${contributor.avatarId}?s=256",
                  profileUrl = "https://github.com/${contributor.login}",
                  modifier = tileModifier,
                  shape = shape,
                )
              }
            }
            index++

            index +=
              creditsSection(
                columns = gridColumns,
                sectionKey = "active",
                titleRes = R.string.hall_of_fame_active_title,
                subtitleRes = R.string.hall_of_fame_active_period,
                icon = { Icon(Icons.RoundedFilled.Bolt, null, Modifier.size(22.dp), tint = colors.primary) },
                state = remainingActive,
                itemKey = { it.profileUrl ?: it.displayName },
                onRetry = { refreshRequest++ },
                activityUrl = "$githubRepoUrl/commits",
                groupedContent = { entries -> HallOfFameActiveBento(entries) },
              ) { contributor, shape, tileModifier ->
                HallOfFamePersonCard(
                  modifier = tileModifier,
                  shape = shape,
                  name = contributor.displayName,
                  details =
                    pluralStringResource(
                      R.plurals.hall_of_fame_commit_count,
                      contributor.contributions,
                      contributor.contributions,
                    ),
                  avatarUrl = contributor.avatarUrl,
                  profileUrl = contributor.profileUrl,
                )
              }

            index +=
              creditsSection(
                columns = gridColumns,
                sectionKey = "community",
                titleRes = R.string.hall_of_fame_feedback_title,
                subtitleRes = R.string.hall_of_fame_feedback_summary,
                icon = { Icon(Icons.RoundedFilled.BugReport, null, Modifier.size(24.dp), tint = colors.primary) },
                state = community,
                itemKey = { it.login },
                onRetry = { refreshRequest++ },
                activityUrl = "$githubRepoUrl/issues",
                expressiveHeader = true,
                highlightedKeys = topReporters.mapTo(mutableSetOf()) { it.login },
                highlightedContent =
                  if (topReporters.isEmpty()) {
                    null
                  } else {
                    {
                      HallOfFameExpressiveReporters(topReporters)
                    }
                  },
              ) { member, shape, tileModifier ->
                HallOfFamePersonCard(
                  modifier = tileModifier,
                  shape = shape,
                  name = member.login,
                  details = communityDetails(member),
                  avatarUrl = member.avatarUrl,
                  profileUrl = member.profileUrl,
                )
              }

            allHeaderIndex[0] = index
            creditsSection(
              columns = gridColumns,
              sectionKey = "all",
              titleRes = R.string.hall_of_fame_all_title,
              subtitleRes = R.string.hall_of_fame_all_period,
              icon = { Icon(Icons.RoundedFilled.Person, null, Modifier.size(24.dp), tint = colors.primary) },
              state = contributors,
              itemKey = { it.profileUrl ?: "anonymous:${it.displayName}" },
              onRetry = { refreshRequest++ },
              activityUrl = "$githubRepoUrl/graphs/contributors",
              expanded = showAll,
              onToggleExpanded = { showAll = !showAll },
            ) { contributor, shape, tileModifier ->
              HallOfFamePersonCard(
                modifier = tileModifier,
                shape = shape,
                name = contributor.displayName,
                details =
                  pluralStringResource(
                    R.plurals.contributors_contribution_count,
                    contributor.contributions,
                    contributor.contributions,
                  ),
                avatarUrl = contributor.avatarUrl,
                profileUrl = contributor.profileUrl,
              )
            }

            item(key = "github", span = { GridItemSpan(maxLineSpan) }) {
              TextButton(
                onClick = { runCatching { uriHandler.openUri("$githubRepoUrl/graphs/contributors") } },
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(stringResource(R.string.hall_of_fame_view_github))
                Icon(Icons.RoundedFilled.ChevronRight, null, modifier = Modifier.size(18.dp))
              }
            }
          }
        }
      }
    }
  }
}

private data class CreditsState<T>(
  val entries: List<T> = emptyList(),
  val loading: Boolean = true,
  val failed: Boolean = false,
)

private val leadLogins = setOf("marlboro-advance", "riteshp2001")

private data class FeaturedContributor(
  val name: String,
  val login: String,
  val avatarId: Long,
)

private val featuredContributors =
  listOf(
    FeaturedContributor("Arnab Sadhukhan", "Arnab11", 25551878L),
    FeaturedContributor("Utsav", "Utsavrajputt", 296386188L),
    FeaturedContributor("SunnyVishnu3", "SunnyVishnu3", 196376335L),
  )

private val featuredLogins = featuredContributors.mapTo(mutableSetOf()) { it.login.lowercase(Locale.ROOT) }

/** Adds one titled section to the grid and returns how many grid items it added. */
private fun <T> LazyGridScope.creditsSection(
  sectionKey: String,
  columns: Int,
  @StringRes titleRes: Int,
  @StringRes subtitleRes: Int,
  icon: @Composable () -> Unit,
  state: CreditsState<T>,
  itemKey: (T) -> String,
  onRetry: () -> Unit,
  activityUrl: String,
  highlightedKeys: Set<String> = emptySet(),
  highlightedContent: (@Composable () -> Unit)? = null,
  groupedContent: (@Composable (List<T>) -> Unit)? = null,
  expressiveHeader: Boolean = true,
  expanded: Boolean = true,
  onToggleExpanded: (() -> Unit)? = null,
  content: @Composable (T, Shape, Modifier) -> Unit,
): Int {
  var added = 0
  item(key = "$sectionKey:header", span = { GridItemSpan(maxLineSpan) }) {
    SectionHeader(
      icon = icon,
      title = stringResource(titleRes),
      subtitle = stringResource(subtitleRes),
      expressive = expressiveHeader,
      onClick = onToggleExpanded,
      trailing = {
        if (!state.loading && !state.failed) {
          CountPill(
            count = state.entries.size,
            expandable = onToggleExpanded != null,
            expanded = expanded,
          )
        }
      },
    )
  }
  added++
  if (expanded && highlightedContent != null) {
    item(key = "$sectionKey:highlights", span = { GridItemSpan(maxLineSpan) }) {
      highlightedContent()
    }
    added++
  }
  if (expanded && (state.loading || state.failed || state.entries.isEmpty())) {
    item(key = "$sectionKey:status", span = { GridItemSpan(maxLineSpan) }) {
      val uriHandler = LocalUriHandler.current
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .clip(rememberConnectedShape(1, 2, ConnectedLayout.Vertical))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          if (state.loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
          Text(
            text =
              stringResource(
                when {
                  state.loading -> R.string.contributors_loading
                  state.failed -> R.string.contributors_load_error
                  else -> R.string.contributors_empty
                },
              ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (state.failed) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.contributors_retry)) }
            TextButton(onClick = { runCatching { uriHandler.openUri(activityUrl) } }) {
              Text(stringResource(R.string.hall_of_fame_view_github))
            }
          }
        }
      }
    }
    added++
  }
  if (expanded) {
    val visible = state.entries.filterNot { itemKey(it) in highlightedKeys }
    if (groupedContent != null && visible.isNotEmpty()) {
      item(key = "$sectionKey:connected", span = { GridItemSpan(maxLineSpan) }) {
        groupedContent(visible)
      }
      added++
    } else {
      // A LazyVerticalGrid cell only measures its own content: its shorter neighbors
      // otherwise leave backgroundless holes. Make one full-span grid item per row,
      // letting IntrinsicSize.Min equalize every card's actual measured height.
      val rowCount = (visible.size + columns - 1) / columns
      items(
        count = rowCount,
        key = { row -> "$sectionKey:row:$row" },
        span = { GridItemSpan(maxLineSpan) },
        contentType = { "person-row" },
      ) { row ->
        Row(
          modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
          horizontalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
        ) {
          visible.drop(row * columns).take(columns).forEachIndexed { column, entry ->
            val index = row * columns + column
            val corners = connectedCornerRadii(index, visible.size, ConnectedLayout.Grid, columns)
            val shape =
              if (index < columns) {
                corners
                  .copy(
                    topStart = AppConnectedShapeTokens.innerCorner,
                    topEnd = AppConnectedShapeTokens.innerCorner,
                  ).asShape()
              } else {
                corners.asShape()
              }
            content(entry, shape, Modifier.weight(1f).fillMaxHeight())
          }
        }
      }
      added += rowCount
    }
  }
  return added
}

@Composable
private fun <T> HallOfFameTopThree(
  entries: List<T>,
  content: @Composable (T, Modifier, Shape) -> Unit,
) {
  val visibleEntries = entries.take(3)
  Row(
    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
    horizontalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
  ) {
    visibleEntries.forEachIndexed { index, entry ->
      content(
        entry,
        Modifier.weight(1f).fillMaxHeight(),
        connectedCornerRadii(index, visibleEntries.size, ConnectedLayout.Horizontal)
          .copy(
            topStart = AppConnectedShapeTokens.innerCorner,
            topEnd = AppConnectedShapeTokens.innerCorner,
          ).asShape(),
      )
    }
    repeat((3 - visibleEntries.size).coerceAtLeast(0)) {
      Spacer(Modifier.weight(1f))
    }
  }
}

/**
 * One connected bento grid, reflowed at compact/tablet widths. Logical grid corners follow
 * the visible contributor order; an incomplete final row stretches to the available width.
 */
@Composable
private fun HallOfFameActiveBento(entries: List<GitHubContributor>) {
  if (entries.isEmpty()) return
  val colors = MaterialTheme.colorScheme
  BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val columns =
      when {
        maxWidth < 340.dp -> 1
        maxWidth >= 700.dp -> 3
        else -> 2
      }
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .clip(MaterialTheme.shapes.extraLarge)
          .background(colors.surfaceContainerLow)
          .padding(AppConnectedShapeTokens.spacing),
      verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
    ) {
      entries.chunked(columns).forEachIndexed { rowIndex, rowEntries ->
        Row(
          modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
          horizontalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
        ) {
          rowEntries.forEachIndexed { columnIndex, contributor ->
            val index = rowIndex * columns + columnIndex
            HallOfFamePersonCard(
              name = contributor.displayName,
              details =
                pluralStringResource(
                  R.plurals.hall_of_fame_commit_count,
                  contributor.contributions,
                  contributor.contributions,
                ),
              avatarUrl = contributor.avatarUrl,
              profileUrl = contributor.profileUrl,
              modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 90.dp),
              shape = rememberConnectedShape(index, entries.size, ConnectedLayout.Grid, columns),
              prominent = index < 2,
            )
          }
        }
      }
    }
  }
}

/** Expressive reporter spotlight, with a full-width lead and an asymmetric connected pair. */
@Composable
private fun HallOfFameExpressiveReporters(entries: List<GitHubCommunityMember>) {
  if (entries.isEmpty()) return
  val colors = MaterialTheme.colorScheme
  val badge = stringResource(R.string.hall_of_fame_tester_badge)
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(MaterialTheme.shapes.extraLarge)
        .background(colors.surfaceContainerLow)
        .padding(AppConnectedShapeTokens.spacing),
    verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
  ) {
    val lead = entries.first()
    val heroShape =
      RoundedCornerShape(
        topStart = 30.dp,
        topEnd = 30.dp,
        bottomEnd = if (entries.size > 1) 8.dp else 30.dp,
        bottomStart = if (entries.size > 1) 8.dp else 30.dp,
      )
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .clip(heroShape)
          .background(colors.tertiaryContainer)
          .profileClickable(lead.login, lead.profileUrl)
          .padding(horizontal = 18.dp, vertical = 20.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      AvatarWithRing(lead.avatarUrl, size = 72.dp)
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          lead.login,
          style = MaterialTheme.typography.titleLarge,
          color = colors.onTertiaryContainer,
          fontWeight = FontWeight.Bold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          communityDetails(lead),
          style = MaterialTheme.typography.bodyMedium,
          color = colors.onTertiaryContainer,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          badge,
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          color = colors.onTertiaryContainer,
        )
      }
      Icon(Icons.RoundedFilled.ChevronRight, null, Modifier.size(24.dp), tint = colors.onTertiaryContainer)
    }
    val remaining = entries.drop(1).take(2)
    if (remaining.isNotEmpty()) {
      BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 320.dp) {
          Column(verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing)) {
            remaining.forEachIndexed { index, member ->
              HallOfFameSpotlightCard(
                name = member.login,
                subtitle = communityDetails(member),
                badge = badge,
                avatarUrl = member.avatarUrl,
                profileUrl = member.profileUrl,
                shape = rememberConnectedShape(index, remaining.size, ConnectedLayout.Vertical),
                modifier = Modifier.fillMaxWidth().heightIn(min = 192.dp),
              )
            }
          }
        } else {
          Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
          ) {
            remaining.forEachIndexed { index, member ->
              HallOfFameSpotlightCard(
                name = member.login,
                subtitle = communityDetails(member),
                badge = badge,
                avatarUrl = member.avatarUrl,
                profileUrl = member.profileUrl,
                shape = rememberConnectedShape(index, remaining.size, ConnectedLayout.Horizontal),
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 208.dp),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun communityDetails(member: GitHubCommunityMember): String =
  buildList {
    if (member.issuesReported > 0) {
      add(pluralStringResource(R.plurals.hall_of_fame_issue_count, member.issuesReported, member.issuesReported))
    }
    if (member.feedbackComments > 0) {
      add(pluralStringResource(R.plurals.hall_of_fame_feedback_count, member.feedbackComments, member.feedbackComments))
    }
  }.joinToString("\n")

// ── Header ───────────────────────────────────────────────────────────────────────────────────

@Composable
private fun HallOfFameHeader(
  showBack: Boolean,
  loading: Boolean,
  onBack: () -> Unit,
  onRefresh: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val titleBrush =
    Brush.horizontalGradient(
      0f to colors.onSurface,
      0.5f to colors.onSurface,
      0.75f to colors.primary,
      1f to colors.tertiary,
    )
  Box(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(bottom = 8.dp)
        .drawBehind {
          val radius = size.width * 0.6f
          val center = Offset(size.width * 0.85f, size.height * 0.45f)
          drawCircle(
            brush =
              Brush.radialGradient(
                colors = listOf(colors.primary.copy(alpha = 0.26f), Color.Transparent),
                center = center,
                radius = radius,
              ),
            radius = radius,
            center = center,
          )
        },
  ) {
    Icon(
      imageVector = Icons.RoundedFilled.Trophy,
      contentDescription = null,
      modifier = Modifier.align(Alignment.TopEnd).padding(top = 44.dp).size(96.dp),
      tint = colors.primary,
    )
    Column {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (showBack) {
          HeaderCircleButton(
            icon = Icons.RoundedFilled.ArrowBack,
            contentDescription = stringResource(R.string.back),
            onClick = onBack,
          )
        } else {
          Spacer(Modifier.size(44.dp))
        }
        HeaderCircleButton(
          icon = Icons.RoundedFilled.Refresh,
          contentDescription = stringResource(R.string.ui_refresh),
          onClick = onRefresh,
          enabled = !loading,
          loading = loading,
        )
      }
      Spacer(Modifier.height(20.dp))
      Text(
        text = stringResource(R.string.pref_hall_of_fame_title),
        modifier = Modifier.padding(end = 88.dp).semantics { heading() },
        style =
          MaterialTheme.typography.displaySmall.copy(
            brush = titleBrush,
            fontWeight = FontWeight.ExtraBold,
          ),
      )
      Text(
        text = stringResource(R.string.hall_of_fame_subtitle),
        modifier = Modifier.padding(top = 4.dp, end = 88.dp),
        style = MaterialTheme.typography.titleSmall,
        color = colors.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun HeaderCircleButton(
  icon: AppIcon,
  contentDescription: String,
  onClick: () -> Unit,
  enabled: Boolean = true,
  loading: Boolean = false,
) {
  val colors = MaterialTheme.colorScheme
  Box(
    modifier =
      Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(colors.surfaceContainerHigh.copy(alpha = 0.85f))
        .border(BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.4f)), CircleShape)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    if (loading) {
      CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    } else {
      Icon(icon, contentDescription, Modifier.size(22.dp), tint = colors.onSurface)
    }
  }
}

// ── Section header ───────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(
  icon: @Composable () -> Unit,
  title: String,
  subtitle: String,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null,
  expressive: Boolean = true,
) {
  val colors = MaterialTheme.colorScheme
  val shape = rememberConnectedShape(0, 2, ConnectedLayout.Vertical)
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(top = 16.dp, bottom = 2.dp)
        .clip(shape)
        .background(if (expressive) colors.secondaryContainer else colors.surfaceContainerLow)
        .border(BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.28f)), shape)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = 14.dp, vertical = if (expressive) 20.dp else 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Box(
      modifier = Modifier.size(48.dp).clip(CircleShape).background(colors.primaryContainer),
      contentAlignment = Alignment.Center,
    ) { icon() }
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        modifier = Modifier.semantics { heading() },
        style = if (expressive) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
      )
    }
    trailing?.invoke()
  }
}

@Composable
private fun ViewAllPill(onClick: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  Row(
    modifier =
      Modifier
        .clip(CircleShape)
        .background(colors.primaryContainer.copy(alpha = 0.8f))
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
      text = stringResource(R.string.hall_of_fame_view_all),
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
      color = colors.onPrimaryContainer,
    )
    Icon(Icons.RoundedFilled.ArrowForward, null, Modifier.size(16.dp), tint = colors.onPrimaryContainer)
  }
}

@Composable
private fun CountPill(
  count: Int,
  expandable: Boolean,
  expanded: Boolean,
) {
  val colors = MaterialTheme.colorScheme
  val arrowRotation by animateFloatAsState(
    targetValue = if (expanded) 180f else 0f,
    animationSpec =
      if (AppMotion.shouldReduceMotion()) AppMotion.ReducedAlpha else AppMotion.Spatial.ExpressiveFast,
    label = "hall_of_fame_expand_rotation",
  )
  Row(
    modifier =
      Modifier
        .clip(CircleShape)
        .background(colors.primaryContainer)
        .padding(horizontal = 14.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      text = NumberFormat.getIntegerInstance().format(count),
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.Bold,
      color = colors.onPrimaryContainer,
    )
    if (expandable) {
      Icon(
        Icons.RoundedFilled.ExpandMore,
        stringResource(if (expanded) R.string.hall_of_fame_hide_section else R.string.hall_of_fame_show_section),
        Modifier.size(18.dp).rotate(arrowRotation),
        tint = colors.onPrimaryContainer,
      )
    }
  }
}

// ── Cards ────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun Modifier.profileClickable(
  name: String,
  profileUrl: String?,
): Modifier {
  val uriHandler = LocalUriHandler.current
  val label = stringResource(R.string.hall_of_fame_view_profile, name)
  return clickable(enabled = profileUrl != null, role = Role.Button, onClickLabel = label) {
    profileUrl?.let { runCatching { uriHandler.openUri(it) } }
  }
}

@Composable
private fun HallOfFameLeadCard(
  name: String,
  handle: String,
  tag: String,
  avatarUrl: String?,
  profileUrl: String?,
  brush: Brush,
  contentColor: Color,
  shape: Shape,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .clip(shape)
        .background(brush)
        .border(BorderStroke(1.dp, contentColor.copy(alpha = 0.14f)), shape)
        .profileClickable(name, profileUrl)
        .padding(horizontal = 14.dp, vertical = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    AvatarWithRing(avatarUrl, size = 72.dp)
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      TagPill(tag, contentColor)
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          text = name,
          modifier = Modifier.weight(1f, fill = false),
          style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
          color = contentColor,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Icon(Icons.RoundedFilled.Verified, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
      }
      Text(
        text = "@$handle",
        style = MaterialTheme.typography.bodyMedium,
        color = contentColor.copy(alpha = 0.7f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (profileUrl != null) {
      Box(
        modifier = Modifier.size(36.dp).clip(CircleShape).background(contentColor.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(Icons.RoundedFilled.ChevronRight, null, Modifier.size(20.dp), tint = contentColor)
      }
    }
  }
}

@Composable
private fun TagPill(
  text: String,
  contentColor: Color,
) {
  Row(
    modifier =
      Modifier
        .clip(CircleShape)
        .background(contentColor.copy(alpha = 0.14f))
        .padding(horizontal = 10.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Icon(Icons.RoundedFilled.Star, null, Modifier.size(12.dp), tint = contentColor)
    Text(
      text = text,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.SemiBold,
      color = contentColor,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun HallOfFameSpotlightCard(
  name: String,
  subtitle: String,
  badge: String,
  avatarUrl: String?,
  profileUrl: String?,
  shape: Shape,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  Box(
    modifier =
      modifier
        .clip(shape)
        .background(colors.secondaryContainer.copy(alpha = 0.65f))
        .border(BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.4f)), shape)
        .profileClickable(name, profileUrl),
  ) {
    Box(
      modifier =
        Modifier
          .align(Alignment.TopEnd)
          .padding(8.dp)
          .size(24.dp)
          .clip(CircleShape)
          .background(colors.primaryContainer),
      contentAlignment = Alignment.Center,
    ) {
      Icon(Icons.RoundedFilled.Crown, null, Modifier.size(14.dp), tint = colors.primary)
    }
    Column(
      modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 26.dp, bottom = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      AvatarWithRing(avatarUrl, size = 60.dp)
      Box(modifier = Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
        Text(
          text = name,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.weight(1f))
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(CircleShape)
            .background(Brush.horizontalGradient(listOf(colors.primary, colors.primary.copy(alpha = 0.82f)))),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.RoundedFilled.Person, null, Modifier.size(16.dp), tint = colors.onPrimary)
        Text(
          text = badge,
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          color = colors.onPrimary,
          maxLines = 1,
        )
      }
    }
  }
}

@Composable
private fun HallOfFamePersonCard(
  name: String,
  details: String,
  avatarUrl: String?,
  profileUrl: String?,
  modifier: Modifier = Modifier,
  shape: Shape = MaterialTheme.shapes.extraLargeIncreased,
  prominent: Boolean = false,
) {
  val colors = MaterialTheme.colorScheme
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .heightIn(min = 72.dp)
        .clip(shape)
        .background(if (prominent) colors.secondaryContainer else colors.surfaceContainerLow)
        .border(BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.35f)), shape)
        .profileClickable(name, profileUrl)
        .padding(horizontal = 10.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    AvatarWithRing(avatarUrl, size = 46.dp, ringWidth = 2.dp)
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = name,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      if (details.isNotBlank()) {
        Text(
          text = details,
          style = MaterialTheme.typography.bodySmall,
          color = colors.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (profileUrl != null) {
      Icon(Icons.RoundedFilled.ChevronRight, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
    }
  }
}

// ── Avatar ───────────────────────────────────────────────────────────────────────────────────

@Composable
private fun AvatarWithRing(
  avatarUrl: String?,
  size: Dp,
  modifier: Modifier = Modifier,
  ringWidth: Dp = 2.5.dp,
) {
  val colors = MaterialTheme.colorScheme
  Box(
    modifier =
      modifier
        .size(size)
        .border(ringWidth, Brush.sweepGradient(listOf(colors.primary, colors.tertiary, colors.primary)), CircleShape)
        .padding(ringWidth + 2.dp),
  ) {
    HallOfFameAvatar(avatarUrl = avatarUrl, accent = colors.primary, modifier = Modifier.fillMaxSize())
  }
}

@Composable
private fun HallOfFameAvatar(
  avatarUrl: String?,
  accent: Color,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier.clip(CircleShape).background(accent.copy(alpha = 0.12f)),
    contentAlignment = Alignment.Center,
  ) {
    Icon(Icons.RoundedFilled.Person, null, modifier = Modifier.size(24.dp), tint = accent)
    avatarUrl?.let { url ->
      RemoteImage(
        url = url,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        cacheTtlMs = AVATAR_CACHE_TTL_MS,
      )
    }
  }
}
