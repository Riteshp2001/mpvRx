/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.dialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import app.gyrolet.mpvrx.data.network.ServerUrlUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import app.gyrolet.mpvrx.presentation.components.AppTextField as OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.ResponsiveBottomSheet
import app.gyrolet.mpvrx.ui.components.themedSegmentedButtonColors
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SharedAddServerDialog(
  isOpen: Boolean,
  isLoading: Boolean,
  errorMessage: String?,
  title: String,
  subtitle: String = "Enter your server address and account details",
  serverUrl: String,
  onServerUrlChange: (String) -> Unit,
  serverUrlPlaceholder: String = "example.com:8096 or 192.168.1.100:8096",
  serverName: String,
  onServerNameChange: (String) -> Unit,
  serverNamePlaceholder: String = "Home Server",
  isTokenAuth: Boolean,
  onAuthModeChange: (isToken: Boolean) -> Unit,
  username: String,
  onUsernameChange: (String) -> Unit,
  password: String,
  onPasswordChange: (String) -> Unit,
  token: String,
  onTokenChange: (String) -> Unit,
  tokenLabel: String = "Access Token / API Key",
  tokenPlaceholder: String = "Paste token or API key",
  tokenSupportingText: String? = null,
  usernameInTokenMode: Boolean = false,
  usernameInTokenModePlaceholder: String? = "Auto-detected from token or enter username",
  canConnect: Boolean,
  onDismiss: () -> Unit,
  onSubmit: () -> Unit,
  headerIcon: (@Composable () -> Unit)? = null,
  additionalFields: (@Composable () -> Unit)? = null,
) {
  if (!isOpen) return

  val sheetState =
    rememberBottomSheetState(
      initialValue = SheetValue.Hidden,
      enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

  var isPasswordVisible by remember { mutableStateOf(false) }

  ResponsiveBottomSheet(
    onDismissRequest = { if (!isLoading) onDismiss() },
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp)
          .navigationBarsPadding(),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // Header
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            if (headerIcon != null) {
              headerIcon()
            }
            Text(
              text = title,
              modifier = Modifier.weight(1f).semantics { heading() },
              style = MaterialTheme.typography.headlineSmallEmphasized,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
          Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(
          onClick = { if (!isLoading) onDismiss() },
          enabled = !isLoading,
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.Close,
            contentDescription = stringResource(R.string.ui_close),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      // Server URL Input
      OutlinedTextField(
        value = serverUrl,
        onValueChange = onServerUrlChange,
        label = { Text("Server Address") },
        placeholder = { Text(serverUrlPlaceholder) },
        leadingIcon = {
          Icon(
            imageVector = Icons.RoundedFilled.BringYourOwnIp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
          )
        },
        trailingIcon = {
          if (serverUrl.isNotEmpty()) {
            IconButton(onClick = { onServerUrlChange("") }) {
              Icon(
                imageVector = Icons.RoundedFilled.Close,
                contentDescription = "Clear",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        },
        supportingText = { Text(ServerUrlUtils.getConnectionHint(serverUrl)) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions =
          KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
          ),
      )

      // Display Name Input
      OutlinedTextField(
        value = serverName,
        onValueChange = onServerNameChange,
        label = { Text("Display Name (Optional)") },
        placeholder = { Text(serverNamePlaceholder) },
        leadingIcon = {
          Icon(
            imageVector = Icons.RoundedFilled.Edit,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions =
          KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Next,
          ),
      )

      // Authentication Method Selector
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = "Authentication Method",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(
          modifier = Modifier.fillMaxWidth(),
        ) {
          SegmentedButton(
            selected = !isTokenAuth,
            onClick = { onAuthModeChange(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            colors = themedSegmentedButtonColors(),
            icon = {
              Icon(
                imageVector = Icons.RoundedFilled.Person,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
            },
          ) {
            Text("Credentials")
          }
          SegmentedButton(
            selected = isTokenAuth,
            onClick = { onAuthModeChange(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            colors = themedSegmentedButtonColors(),
            icon = {
              Icon(
                imageVector = Icons.RoundedFilled.Security,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
            },
          ) {
            Text("API Token")
          }
        }
      }

      // Conditional Auth Fields
      if (!isTokenAuth) {
        OutlinedTextField(
          value = username,
          onValueChange = onUsernameChange,
          label = { Text("Username") },
          leadingIcon = {
            Icon(
              imageVector = Icons.RoundedFilled.Person,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          },
          singleLine = true,
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.fillMaxWidth(),
          keyboardOptions =
            KeyboardOptions(
              keyboardType = KeyboardType.Text,
              imeAction = ImeAction.Next,
            ),
        )

        OutlinedTextField(
          value = password,
          onValueChange = onPasswordChange,
          label = { Text("Password") },
          leadingIcon = {
            Icon(
              imageVector = Icons.RoundedFilled.Lock,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          },
          singleLine = true,
          shape = RoundedCornerShape(16.dp),
          visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
          keyboardOptions =
            KeyboardOptions(
              keyboardType = if (isPasswordVisible) KeyboardType.Text else KeyboardType.Password,
              imeAction = ImeAction.Done,
            ),
          keyboardActions = KeyboardActions(onDone = { onSubmit() }),
          trailingIcon = {
            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
              Icon(
                imageVector = if (isPasswordVisible) Icons.RoundedFilled.VisibilityOff else Icons.RoundedFilled.Visibility,
                contentDescription = if (isPasswordVisible) "Hide password" else "Show password",
              )
            }
          },
          modifier = Modifier.fillMaxWidth(),
        )
      } else {
        if (usernameInTokenMode) {
          OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("Username (Optional)") },
            placeholder = { usernameInTokenModePlaceholder?.let { Text(it) } },
            leadingIcon = {
              Icon(
                imageVector = Icons.RoundedFilled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions =
              KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
              ),
          )
        }

        OutlinedTextField(
          value = token,
          onValueChange = onTokenChange,
          label = { Text(tokenLabel) },
          placeholder = { Text(tokenPlaceholder) },
          leadingIcon = {
            Icon(
              imageVector = Icons.RoundedFilled.Security,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          },
          supportingText = tokenSupportingText?.let { { Text(it) } },
          singleLine = true,
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.fillMaxWidth(),
          keyboardOptions =
            KeyboardOptions(
              keyboardType = KeyboardType.Password,
              imeAction = ImeAction.Done,
            ),
          keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        )
      }

      if (additionalFields != null) {
        additionalFields()
      }

      // Animated Error Card
      AnimatedVisibility(
        visible = !errorMessage.isNullOrBlank(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
      ) {
        Card(
          shape = RoundedCornerShape(14.dp),
          colors =
            CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Warning,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.size(22.dp),
            )
            Text(
              text = errorMessage ?: "",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.weight(1f),
            )
          }
        }
      }

      // Action Buttons
      Button(
        onClick = onSubmit,
        enabled = canConnect && !isLoading,
        shapes = ButtonDefaults.shapes(),
        modifier =
          Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
      ) {
        if (isLoading) {
          CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.5.dp,
            color = MaterialTheme.colorScheme.onPrimary,
          )
          Spacer(modifier = Modifier.width(10.dp))
          Text(
            text = stringResource(R.string.ui_connecting),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
        } else {
          Icon(
            imageVector = Icons.RoundedFilled.Link,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = stringResource(R.string.ui_connect),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> SharedManageServersDialog(
  isOpen: Boolean,
  title: String,
  servers: List<T>,
  activeServerId: Long?,
  getServerId: (T) -> Long,
  getServerName: (T) -> String,
  getServerUrl: (T) -> String,
  getServerSubtitle: ((T) -> String?)? = null,
  onDismiss: () -> Unit,
  onSelectServer: (T) -> Unit,
  onDeleteServer: (T) -> Unit,
  onAddServerClick: () -> Unit,
  avatarContent: (@Composable (server: T, isSelected: Boolean) -> Unit)? = null,
) {
  if (!isOpen) return

  val sheetState =
    rememberBottomSheetState(
      initialValue = SheetValue.Hidden,
      enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

  ResponsiveBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp)
          .navigationBarsPadding(),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // Header
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "${servers.size} configured server${if (servers.size == 1) "" else "s"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        TextButton(onClick = onDismiss) {
          Text(stringResource(R.string.ui_close))
        }
      }

      if (servers.isEmpty()) {
        Card(
          shape = RoundedCornerShape(16.dp),
          colors =
            CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.BringYourOwnIp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
              )
              Text(
                text = "No servers connected yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          servers.forEach { server ->
            val serverId = getServerId(server)
            val isSelected = activeServerId != null && serverId == activeServerId
            Surface(
              shape = RoundedCornerShape(16.dp),
              color =
                if (isSelected) {
                  MaterialTheme.colorScheme.primaryContainer
                } else {
                  MaterialTheme.colorScheme.surfaceContainer
                },
              modifier =
                Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(16.dp))
                  .semantics { selected = isSelected }
                  .clickable {
                    onSelectServer(server)
                    onDismiss()
                  },
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
              ) {
                if (avatarContent != null) {
                  avatarContent(server, isSelected)
                } else {
                  Surface(
                    shape = CircleShape,
                    color =
                      if (isSelected) {
                        MaterialTheme.colorScheme.primary
                      } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                      },
                    modifier = Modifier.size(40.dp),
                  ) {
                    Box(
                      modifier = Modifier.fillMaxSize(),
                      contentAlignment = Alignment.Center,
                    ) {
                      Icon(
                        imageVector = if (isSelected) Icons.RoundedFilled.Check else Icons.RoundedFilled.BringYourOwnIp,
                        contentDescription = null,
                        tint =
                          if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                          } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                          },
                        modifier = Modifier.size(20.dp),
                      )
                    }
                  }
                }

                Column(modifier = Modifier.weight(1f)) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    Text(
                      text = getServerName(server),
                      modifier = Modifier.weight(1f),
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                      color =
                        if (isSelected) {
                          MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                          MaterialTheme.colorScheme.onSurface
                        },
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                    if (isSelected) {
                      Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(2.dp),
                      ) {
                        Text(
                          text = "Active",
                          style = MaterialTheme.typography.labelSmall,
                          color = MaterialTheme.colorScheme.onPrimary,
                          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                      }
                    }
                  }
                  Text(
                    text = getServerUrl(server),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                      if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                      } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                      },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  val subtitle = getServerSubtitle?.invoke(server)
                  if (!subtitle.isNullOrBlank()) {
                    Text(
                      text = subtitle,
                      style = MaterialTheme.typography.labelSmall,
                      color =
                        if (isSelected) {
                          MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                        } else {
                          MaterialTheme.colorScheme.outline
                        },
                      maxLines = 1,
                    )
                  }
                }

                IconButton(
                  onClick = { onDeleteServer(server) },
                  modifier = Modifier.size(48.dp),
                ) {
                  Icon(
                    imageVector = Icons.RoundedFilled.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                  )
                }
              }
            }
          }
        }
      }

      FilledTonalButton(
        onClick = {
          onDismiss()
          onAddServerClick()
        },
        shapes = ButtonDefaults.shapes(),
        modifier =
          Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.Add,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Add Another Server")
      }

      Spacer(modifier = Modifier.height(12.dp))
    }
  }
}
