package com.example.ui.screens.models

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.error.AiError
import com.example.core.model.AiModelInfo
import com.example.core.model.ProviderConfig
import com.example.core.model.ProviderStatus
import com.example.ui.components.CapabilityBadge
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.ProviderLogoBadge
import com.example.ui.components.pressScale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelPickerSheet(
    providers: List<ProviderConfig>,
    models: List<AiModelInfo>,
    selectedProviderId: String?,
    selectedModelId: String?,
    refreshingProviderIds: Set<String> = emptySet(),
    modelLoadErrors: Map<String, AiError> = emptyMap(),
    onSelectModel: (ProviderConfig, AiModelInfo) -> Unit,
    onAddManualModel: (providerId: String, modelId: String) -> Unit,
    onRefreshModels: ((providerId: String) -> Unit)? = null,
    onRefreshAll: (() -> Unit)? = null,
    onOpenProviders: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var filterProviderId by remember { mutableStateOf<String?>(null) }
    var manualDialogProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    var manualModelText by remember { mutableStateOf("") }

    val enabledProviders = remember(providers, models) {
        providers.filter { it.isEnabled }
            .sortedWith(
                compareByDescending<ProviderConfig> { p -> models.any { it.providerId == p.id } }
                    .thenByDescending { it.status == ProviderStatus.CONNECTED }
                    .thenBy { it.sortOrder },
            )
    }
    val visibleProviders = remember(enabledProviders, filterProviderId) {
        if (filterProviderId == null) {
            enabledProviders
        } else {
            enabledProviders.filter { it.id == filterProviderId }.ifEmpty { enabledProviders }
        }
    }
    val modelsByProvider = remember(models, searchQuery, visibleProviders) {
        val q = searchQuery.trim().lowercase()
        visibleProviders.associateWith { provider ->
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
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.models_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (onRefreshAll != null && enabledProviders.isNotEmpty()) {
                    val isAnyRefreshing = refreshingProviderIds.isNotEmpty()
                    TextButton(
                        onClick = onRefreshAll,
                        enabled = !isAnyRefreshing,
                        modifier = Modifier.testTag("model_picker_refresh_all"),
                    ) {
                        if (isAnyRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.provider_models_refresh_all))
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = stringResource(R.string.models_search_hint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cd_close))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("model_search_input"),
            )
            if (enabledProviders.size > 1) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = filterProviderId == null,
                        onClick = { filterProviderId = null },
                        label = { Text(stringResource(R.string.models_filter_all)) },
                    )
                    enabledProviders.forEach { provider ->
                        val count = models.count { it.providerId == provider.id }
                        FilterChip(
                            selected = filterProviderId == provider.id,
                            onClick = {
                                filterProviderId = if (filterProviderId == provider.id) null else provider.id
                            },
                            leadingIcon = {
                                ProviderLogoBadge(providerType = provider.type, size = 16.dp)
                            },
                            label = {
                                Text(
                                    text = "${provider.displayName} ($count)",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            if (enabledProviders.isEmpty()) {
                EmptyStatePanel(
                    icon = Icons.Outlined.Tune,
                    title = stringResource(R.string.models_empty),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (onOpenProviders != null) {
                        FilledTonalButton(
                            onClick = {
                                onDismiss()
                                onOpenProviders()
                            },
                        ) {
                            Text(stringResource(R.string.providers_onboarding_title))
                        }
                    }
                }
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
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (onRefreshModels != null) {
                                        if (isRefreshing) {
                                            CircularProgressIndicator(
                                                modifier = Modifier
                                                    .padding(horizontal = 14.dp)
                                                    .size(18.dp),
                                                strokeWidth = 2.dp,
                                            )
                                        } else {
                                            IconButton(onClick = { onRefreshModels(provider.id) }) {
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
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = stringResource(R.string.provider_add_manual_model),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                            }
                        }

                        if (providerModels.isEmpty() && searchQuery.isBlank()) {
                            item(key = "empty_${provider.id}") {
                                val isRefreshing = provider.id in refreshingProviderIds
                                val loadErr = modelLoadErrors[provider.id]
                                val errMsg = loadErr?.providerMessage?.takeIf { it.isNotBlank() }
                                    ?: provider.lastCheckMessage.takeIf {
                                        provider.status == ProviderStatus.ERROR && it.isNotBlank()
                                    }

                                when {
                                    isRefreshing -> {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 34.dp, top = 4.dp, bottom = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                            )
                                            Text(
                                                text = stringResource(R.string.chat_loading_models),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    errMsg != null -> {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.28f)),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 8.dp, bottom = 4.dp),
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.chat_models_load_failed, errMsg),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                                )
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    if (onRefreshModels != null) {
                                                        TextButton(
                                                            onClick = { onRefreshModels(provider.id) },
                                                            modifier = Modifier.testTag("retry_provider_models_${provider.id}"),
                                                        ) {
                                                            Text(stringResource(R.string.action_retry))
                                                        }
                                                    }
                                                    if (onOpenProviders != null) {
                                                        TextButton(
                                                            onClick = {
                                                                onDismiss()
                                                                onOpenProviders()
                                                            },
                                                        ) {
                                                            Text(stringResource(R.string.providers_configure))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    else -> {
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
                                                    modifier = Modifier.testTag("fetch_models_provider_${provider.id}"),
                                                ) {
                                                    Text(stringResource(R.string.chat_load_models))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            items(providerModels, key = { "m_${provider.id}_${it.modelId}" }) { model ->
                                val isSelected = provider.id == selectedProviderId && model.modelId == selectedModelId
                                val itemInteraction = remember { MutableInteractionSource() }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
                                    } else {
                                        MaterialTheme.colorScheme.background
                                    },
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                        },
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateItem()
                                        .pressScale(itemInteraction)
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable(
                                            interactionSource = itemInteraction,
                                            indication = ripple(),
                                        ) {
                                            onSelectModel(provider, model)
                                            onDismiss()
                                        }
                                        .testTag("model_item_${model.modelId}"),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = model.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (model.capabilities.isNotEmpty()) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                FlowRow(
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                                ) {
                                                    model.capabilities.forEach { cap ->
                                                        CapabilityBadge(capability = cap)
                                                    }
                                                }
                                            }
                                        }
                                        AnimatedVisibility(visible = isSelected) {
                                            Icon(
                                                imageVector = Icons.Outlined.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(start = 8.dp),
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
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.provider_manual_model_title)) },
            text = {
                OutlinedTextField(
                    value = manualModelText,
                    onValueChange = { manualModelText = it },
                    label = { Text(stringResource(R.string.provider_manual_model_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (manualModelText.isNotBlank()) {
                            onAddManualModel(target.id, manualModelText.trim())
                        }
                        manualDialogProvider = null
                    },
                    enabled = manualModelText.isNotBlank(),
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
