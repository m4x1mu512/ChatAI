package com.example.ui.screens.providers

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.model.AiModelInfo
import com.example.core.model.ProviderConfig
import com.example.core.model.ProviderStatus
import com.example.core.model.ProviderType
import com.example.ui.components.ProviderLogoBadge
import com.example.ui.components.ProviderStatusChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(
    viewModel: ProvidersViewModel,
    isFirstLaunchOnboarding: Boolean,
    onAddProvider: (ProviderType) -> Unit,
    onEditProvider: (String) -> Unit,
    onContinueToChats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val configured by viewModel.configuredProviders.collectAsStateWithLifecycle()
    val modelsByProvider by viewModel.modelsByProvider.collectAsStateWithLifecycle()
    val refreshingIds by viewModel.refreshingProviderIds.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var pendingDelete by remember { mutableStateOf<ProviderConfig?>(null) }

    val configuredByType = remember(configured) { configured.groupBy { it.type } }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.providers_onboarding_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    if (isFirstLaunchOnboarding || configured.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                viewModel.skipOnboarding()
                                onContinueToChats()
                            },
                            modifier = Modifier.testTag("continue_to_chats_button"),
                        ) {
                            Text(stringResource(R.string.providers_skip_to_chats))
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "hero") {
                ProvidersHeroBanner()
            }

            if (configured.isNotEmpty()) {
                item(key = "configured_header") {
                    Text(
                        text = stringResource(R.string.providers_configured_section),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }

                items(configured, key = { "cfg_${it.id}" }) { provider ->
                    ConfiguredProviderCard(
                        provider = provider,
                        models = modelsByProvider[provider.id].orEmpty(),
                        isRefreshingModels = provider.id in refreshingIds,
                        onEdit = { onEditProvider(provider.id) },
                        onToggle = { enabled -> viewModel.toggleProviderEnabled(provider.id, enabled) },
                        onRefreshModels = { viewModel.refreshModelsForProvider(provider.id) },
                        onSelectModel = { modelId -> viewModel.selectProviderDefaultModel(provider.id, modelId) },
                        onDelete = { pendingDelete = provider },
                    )
                }
            }

            item(key = "catalog_header") {
                Text(
                    text = stringResource(R.string.providers_catalog_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }

            items(ProviderType.entries, key = { "type_${it.name}" }) { type ->
                val existingAccounts = configuredByType[type].orEmpty()
                PresetProviderCard(
                    type = type,
                    existingAccounts = existingAccounts,
                    onConfigureOrAdd = { onAddProvider(type) },
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.providers_delete_title)) },
            text = { Text(stringResource(R.string.providers_delete_message, target.displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProvider(target.id)
                        pendingDelete = null
                    },
                    modifier = Modifier.testTag("confirm_delete_provider"),
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ProvidersHeroBanner() {
    Card(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_providers_hero),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xCC0F172A),
                                Color(0xEE0F172A),
                            ),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Key,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Android Keystore AES-256",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF38BDF8),
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.providers_onboarding_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.92f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfiguredProviderCard(
    provider: ProviderConfig,
    models: List<AiModelInfo>,
    isRefreshingModels: Boolean,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRefreshModels: () -> Unit,
    onSelectModel: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var modelsExpanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onEdit)
            .testTag("configured_provider_${provider.id}"),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProviderLogoBadge(providerType = provider.type)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = provider.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        ProviderStatusChip(status = provider.status)
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            append(provider.baseUrl)
                            if (provider.maskedApiKey.isNotBlank()) {
                                append(" • ")
                                append(provider.maskedApiKey)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = provider.isEnabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("switch_provider_${provider.id}"),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = provider.resolvedProtocol.name.replace("_", "-").lowercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    if (models.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        ) {
                            Text(
                                text = stringResource(R.string.provider_models_section, models.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isRefreshingModels) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = 10.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = onRefreshModels) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.provider_models_refresh),
                            )
                        }
                    }
                    IconButton(onClick = onEdit) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.action_edit),
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (models.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                val visibleModels = if (modelsExpanded) models else models.take(6)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    visibleModels.forEach { model ->
                        val isSelected = model.modelId == provider.selectedModelId
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectModel(model.modelId) },
                            label = {
                                Text(
                                    text = model.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = if (isSelected) {
                                {
                                    Icon(
                                        imageVector = Icons.Outlined.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
                if (models.size > 6) {
                    TextButton(
                        onClick = { modelsExpanded = !modelsExpanded },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = if (modelsExpanded) {
                                stringResource(R.string.provider_models_hide)
                            } else {
                                stringResource(R.string.provider_models_show_all, models.size)
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (modelsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            } else if (provider.status == ProviderStatus.CONNECTED || provider.hasApiKey) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = onRefreshModels,
                    enabled = !isRefreshingModels,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.provider_fetch_models))
                }
            }
        }
    }
}

@Composable
private fun PresetProviderCard(
    type: ProviderType,
    existingAccounts: List<ProviderConfig>,
    onConfigureOrAdd: () -> Unit,
) {
    val bestStatus = when {
        existingAccounts.any { it.status == ProviderStatus.CONNECTED } -> ProviderStatus.CONNECTED
        existingAccounts.any { it.status == ProviderStatus.ERROR } -> ProviderStatus.ERROR
        else -> ProviderStatus.NOT_CONFIGURED
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onConfigureOrAdd)
            .testTag("preset_provider_${type.name.lowercase()}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderLogoBadge(providerType = type)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = type.defaultDisplayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ProviderStatusChip(status = bestStatus)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(type.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onConfigureOrAdd,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (existingAccounts.isEmpty()) {
                        stringResource(R.string.providers_configure)
                    } else {
                        stringResource(R.string.providers_add_account)
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
