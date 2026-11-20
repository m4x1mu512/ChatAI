package com.example.ui.screens.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.model.AiModelInfo
import com.example.core.model.ProviderConfig
import com.example.core.model.ProviderStatus
import com.example.ui.components.CapabilityBadge
import com.example.ui.components.ProviderLogoBadge

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelPickerSheet(
    providers: List<ProviderConfig>,
    models: List<AiModelInfo>,
    selectedProviderId: String?,
    selectedModelId: String?,
    refreshingProviderIds: Set<String> = emptySet(),
    onSelectModel: (ProviderConfig, AiModelInfo) -> Unit,
    onAddManualModel: (providerId: String, modelId: String) -> Unit,
    onRefreshModels: ((providerId: String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var manualDialogProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    var manualModelText by remember { mutableStateOf("") }

    val enabledProviders = remember(providers) {
        providers.filter { it.isEnabled && (it.status == ProviderStatus.CONNECTED || it.hasApiKey || !it.type.requiresApiKey) }
    }
    val modelsByProvider = remember(models, searchQuery, enabledProviders) {
        val q = searchQuery.trim().lowercase()
        enabledProviders.associateWith { provider ->
            models.filter { it.providerId == provider.id }
                .filter {
                    q.isEmpty() ||
                        it.displayName.lowercase().contains(q) ||
                        it.modelId.lowercase().contains(q)
                }
                .sortedBy { it.displayName.lowercase() }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.models_picker_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.models_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("model_search_input"),
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (enabledProviders.isEmpty()) {
                Text(
                    text = stringResource(R.string.models_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    modelsByProvider.forEach { (provider, providerModels) ->
                        item(key = "hdr_${provider.id}") {
                            val isRefreshing = provider.id in refreshingProviderIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    ProviderLogoBadge(providerType = provider.type, size = 26.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${provider.displayName} (${providerModels.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (onRefreshModels != null) {
                                        if (isRefreshing) {
                                            CircularProgressIndicator(
                                                modifier = Modifier
                                                    .padding(horizontal = 8.dp)
                                                    .size(16.dp),
                                                strokeWidth = 2.dp,
                                            )
                                        } else {
                                            IconButton(
                                                onClick = { onRefreshModels(provider.id) },
                                                modifier = Modifier.size(36.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Refresh,
                                                    contentDescription = stringResource(R.string.provider_models_refresh),
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                    }
                                    TextButton(
                                        onClick = {
                                            manualModelText = ""
                                            manualDialogProvider = provider
                                        },
                                    ) {
                                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text(stringResource(R.string.provider_add_manual_model), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }

                        if (providerModels.isEmpty() && searchQuery.isBlank()) {
                            item(key = "empty_${provider.id}") {
                                Row(
                                    modifier = Modifier.padding(start = 34.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.provider_manual_model_note),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (onRefreshModels != null) {
                                        TextButton(
                                            onClick = { onRefreshModels(provider.id) },
                                            enabled = provider.id !in refreshingProviderIds,
                                        ) {
                                            Text(stringResource(R.string.provider_fetch_models))
                                        }
                                    }
                                }
                            }
                        } else {
                            items(providerModels, key = { "m_${provider.id}_${it.modelId}" }) { model ->
                                val isSelected = provider.id == selectedProviderId && model.modelId == selectedModelId
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            onSelectModel(provider, model)
                                            onDismiss()
                                        }
                                        .testTag("model_item_${model.modelId}"),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = model.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                model.capabilities.forEach { cap ->
                                                    CapabilityBadge(capability = cap)
                                                }
                                            }
                                        }
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Outlined.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    manualDialogProvider?.let { target ->
        AlertDialog(
            onDismissRequest = { manualDialogProvider = null },
            title = { Text(stringResource(R.string.provider_manual_model_title)) },
            text = {
                OutlinedTextField(
                    value = manualModelText,
                    onValueChange = { manualModelText = it },
                    label = { Text(stringResource(R.string.provider_manual_model_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (manualModelText.isNotBlank()) {
                            onAddManualModel(target.id, manualModelText.trim())
                        }
                        manualDialogProvider = null
                    },
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { manualDialogProvider = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
