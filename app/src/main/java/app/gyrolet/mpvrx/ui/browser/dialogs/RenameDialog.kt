package app.gyrolet.mpvrx.ui.browser.dialogs

import androidx.compose.ui.focus.FocusRequester

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import app.gyrolet.mpvrx.ui.icons.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.AiPreferences
import app.gyrolet.mpvrx.repository.ai.AiService
import app.gyrolet.mpvrx.ui.icons.Icons
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RenameDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  onConfirm: (String) -> Unit,
  currentName: String,
  itemType: String,
  extension: String? = null,
) {
  if (!isOpen) return

  val baseName = remember(currentName) { mutableStateOf(currentName) }
  val isError = remember { mutableStateOf(false) }
  val errorMessage = remember { mutableStateOf("") }
  val focusRequester = remember { FocusRequester() }
  val scope = rememberCoroutineScope()
  val aiService = koinInject<AiService>()
  val aiPreferences = koinInject<AiPreferences>()
  var isAiLoading by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    focusRequester.requestFocus()
  }

  fun validateAndConfirm() {
    when {
      baseName.value.isBlank() -> {
        isError.value = true
        errorMessage.value = "Name cannot be empty"
      }

      baseName.value.contains("/") || baseName.value.contains("\\") -> {
        isError.value = true
        errorMessage.value = "Name cannot contain / or \\"
      }

      else -> {
        onConfirm(baseName.value + (extension ?: ""))
        onDismiss()
      }
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = "Rename $itemType",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
      )
    },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        OutlinedTextField(
          value = baseName.value,
          onValueChange = {
            baseName.value = it
            isError.value = false
            errorMessage.value = ""
          },
          modifier =
            Modifier
              .fillMaxWidth()
              .focusRequester(focusRequester),
          label = { Text("New name", fontWeight = FontWeight.Medium) },
          singleLine = false,
          maxLines = 5,
          isError = isError.value,
          supportingText =
            if (isError.value) {
              { Text(errorMessage.value) }
            } else {
              null
            },
          colors =
            OutlinedTextFieldDefaults.colors(
              focusedBorderColor = MaterialTheme.colorScheme.primary,
              focusedLabelColor = MaterialTheme.colorScheme.primary,
            ),
          keyboardOptions =
            KeyboardOptions(
              imeAction = ImeAction.Done,
            ),
          keyboardActions =
            KeyboardActions(
              onDone = { validateAndConfirm() },
            ),
          shape = MaterialTheme.shapes.extraLarge,
        )

        if (aiPreferences.enabled.get() && aiPreferences.renameWithAi.get()) {
          OutlinedButton(
            onClick = {
              scope.launch {
                isAiLoading = true
                aiService.renameWithAi(currentName, extension)
                  .onSuccess { aiName ->
                    baseName.value = aiName.removeSuffix(extension ?: "")
                  }
                  .onFailure { _ ->
                    isError.value = true
                    errorMessage.value = "AI rename failed. Check API key and model."
                  }
                isAiLoading = false
              }
            },
            enabled = !isAiLoading,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
          ) {
            if (isAiLoading) {
              CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text("AI is thinking...")
            } else {
              Icon(
                imageVector = Icons.RoundedFilled.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text("AI Rename", fontWeight = FontWeight.Medium)
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { validateAndConfirm() },
        enabled = baseName.value.isNotBlank(),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
          ),
        shape = MaterialTheme.shapes.extraLarge,
      ) {
        Text(
          text = "Rename",
          fontWeight = FontWeight.Bold,
        )
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
      ) {
        Text("Cancel", fontWeight = FontWeight.Medium)
      }
    },
    containerColor = MaterialTheme.colorScheme.surface,
    tonalElevation = 6.dp,
    shape = MaterialTheme.shapes.extraLarge,
  )
}
