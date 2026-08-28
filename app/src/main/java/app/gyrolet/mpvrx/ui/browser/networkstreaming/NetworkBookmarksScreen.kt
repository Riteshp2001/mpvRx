package app.gyrolet.mpvrx.ui.browser.networkstreaming

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.preferences.NetworkBookmarkPreferences
import app.gyrolet.mpvrx.preferences.NetworkFolderBookmark
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.repository.NetworkRepository
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.tv.tvFocusable
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

internal data class ResolvedNetworkFolderBookmark(
  val bookmark: NetworkFolderBookmark,
  val connection: NetworkConnection,
) {
  val stableKey: String
    get() = "${connection.id}:${bookmark.path}"
}

internal fun resolveNetworkFolderBookmarks(
  bookmarks: List<NetworkFolderBookmark>,
  connections: List<NetworkConnection>,
): List<ResolvedNetworkFolderBookmark> {
  val connectionsById = connections.associateBy(NetworkConnection::id)
  return bookmarks.mapNotNull { bookmark ->
    connectionsById[bookmark.connectionId]?.let { connection ->
      ResolvedNetworkFolderBookmark(bookmark, connection)
    }
  }
}

@Composable
internal fun NetworkBookmarkSection(
  bookmarks: List<ResolvedNetworkFolderBookmark>,
  onOpen: (ResolvedNetworkFolderBookmark) -> Unit,
  onManage: () -> Unit,
) {
  if (bookmarks.isEmpty()) return

  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.network_bookmarks_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.weight(1f),
      )
      IconButton(onClick = onManage) {
        Icon(
          imageVector = Icons.RoundedFilled.ChevronRight,
          contentDescription = stringResource(R.string.network_bookmarks_manage),
        )
      }
    }

    LazyRow(
      contentPadding = PaddingValues(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      items(bookmarks, key = ResolvedNetworkFolderBookmark::stableKey) { bookmark ->
        BookmarkQuickCard(bookmark = bookmark, onClick = { onOpen(bookmark) })
      }
    }
  }
}

@Composable
private fun BookmarkQuickCard(
  bookmark: ResolvedNetworkFolderBookmark,
  onClick: () -> Unit,
) {
  Card(
    modifier =
      Modifier
        .width(208.dp)
        .tvFocusable(RoundedCornerShape(8.dp))
        .clickable(onClick = onClick),
    shape = RoundedCornerShape(8.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = Icons.RoundedFilled.Folder,
        contentDescription = null,
        modifier = Modifier.size(38.dp),
        tint = MaterialTheme.colorScheme.secondary,
      )
      Text(
        text = bookmark.bookmark.folderName,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text =
          stringResource(
            R.string.network_bookmark_source_format,
            bookmark.connection.name,
            bookmark.bookmark.folderName,
          ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Serializable
object NetworkBookmarksScreen : Screen {
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val bookmarkPreferences = koinInject<NetworkBookmarkPreferences>()
    val networkRepository = koinInject<NetworkRepository>()
    val bookmarks by bookmarkPreferences.bookmarks.collectAsState()
    val connectionFlow = remember(networkRepository) { networkRepository.getAllConnections() }
    val connections by connectionFlow.collectAsState(initial = emptyList())
    val resolvedBookmarks = remember(bookmarks, connections) {
      resolveNetworkFolderBookmarks(bookmarks, connections)
    }

    Scaffold(
      topBar = {
        BrowserTopBar(
          title = stringResource(R.string.network_bookmarks_title),
          isInSelectionMode = false,
          selectedCount = 0,
          totalCount = resolvedBookmarks.size,
          onBackClick = { backstack.popSafely() },
          onCancelSelection = {},
        )
      },
    ) { padding ->
      if (resolvedBookmarks.isEmpty()) {
        Box(
          modifier = Modifier.fillMaxSize().padding(padding),
          contentAlignment = Alignment.Center,
        ) {
          EmptyState(
            icon = Icons.RoundedFilled.Folder,
            title = stringResource(R.string.network_bookmarks_empty_title),
            message = stringResource(R.string.network_bookmarks_empty_description),
          )
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize().padding(padding),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(resolvedBookmarks, key = ResolvedNetworkFolderBookmark::stableKey) { bookmark ->
            BookmarkManageCard(
              bookmark = bookmark,
              onOpen = {
                backstack.add(
                  NetworkBrowserScreen(
                    connectionId = bookmark.connection.id,
                    connectionName = bookmark.connection.name,
                    currentPath = bookmark.bookmark.path,
                  ),
                )
              },
              onRemove = { bookmarkPreferences.remove(bookmark.bookmark) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun BookmarkManageCard(
  bookmark: ResolvedNetworkFolderBookmark,
  onOpen: () -> Unit,
  onRemove: () -> Unit,
) {
  Card(
    modifier =
      Modifier
        .fillMaxWidth()
        .tvFocusable(RoundedCornerShape(8.dp))
        .clickable(onClick = onOpen),
    shape = RoundedCornerShape(8.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.RoundedFilled.Folder,
        contentDescription = null,
        modifier = Modifier.size(40.dp),
        tint = MaterialTheme.colorScheme.secondary,
      )
      Spacer(modifier = Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = bookmark.bookmark.folderName,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text =
            stringResource(
              R.string.network_bookmark_source_format,
              bookmark.connection.name,
              bookmark.bookmark.path,
            ),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      IconButton(onClick = onRemove) {
        Icon(
          imageVector = Icons.RoundedFilled.Delete,
          contentDescription = stringResource(R.string.network_bookmark_remove),
          tint = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}