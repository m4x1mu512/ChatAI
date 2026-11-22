package com.example.ui.screens.providers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.model.ApiProtocol
import com.example.core.model.ProviderType
import com.example.ui.components.CapabilityBadge
import com.example.ui.components.ErrorReportCard
import com.example.ui.components.ProviderLogoBadge
import com.example.ui.components.ProviderStatusChip
import com.example.ui.components.pressScale
import com.example.ui.theme.semanticColors
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProviderConfigScreen(
    viewModel: ProvidersViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.editorState.collectAsStateWithLifecycle()
    var showApiKey by remember { mutableStateOf(false) }
    var modelSearchQuery by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedText = stringResource(R.string.error_copied)

    val filteredModels = remember(state.models, modelSearchQuery) {
        val q = modelSearchQuery.trim().lowercase()
        if (q.isEmpty()) {
            state.models
        } else {
            state.models.filter {
                it.displayName.lowercase().contains(q) || it.modelId.lowercase().contains(q)
            }
        }
    }

    val semantic = MaterialTheme.semanticColors

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.provider_config_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = { viewModel.saveAndFetchModels(onSaved = onBack) },
                        enabled = !state.isSaving,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("save_provider_button"),
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(stringResource(R.string.action_save))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "header_summary") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ProviderLogoBadge(providerType = state.type, size = 48.dp)
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                                ) {
                                    Text(
                                        text = state.type.defaultDisplayName,
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                    ProviderStatusChip(status = state.status)
                                }
                                Text(
                                    text = stringResource(state.type.descriptionRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

            item(key = "name_and_url") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.displayName,
                        onValueChange = viewModel::updateDisplayName,
                        label = { Text(stringResource(R.string.provider_field_name)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("provider_name_input"),
                    )

                    val isCloudflare = state.type == ProviderType.CLOUDFLARE
                    val hasPlaceholderAccountId = state.baseUrl.contains("YOUR_ACCOUNT_ID", ignoreCase = true)

                    OutlinedTextField(
                        value = state.baseUrl,
                        onValueChange = viewModel::updateBaseUrl,
                        label = { Text(stringResource(R.string.provider_field_base_url)) },
                        isError = isCloudflare && hasPlaceholderAccountId,
                        supportingText = if (isCloudflare && hasPlaceholderAccountId) {
                            { Text(stringResource(R.string.provider_cloudflare_url_warning)) }
                        } else {
                            null
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("provider_base_url_input"),
                    )

                    if (isCloudflare) {
                        val accountIdFromUrl = remember(state.baseUrl) {
                            Regex("""/accounts/([^/]+)(?=/|$)""")
                                .find(state.baseUrl)
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.takeUnless { it.equals("YOUR_ACCOUNT_ID", ignoreCase = true) }
                                .orEmpty()
                        }
                        OutlinedTextField(
                            value = accountIdFromUrl,
                            onValueChange = { entered ->
                                val cleanId = entered.trim()
                                val replacement = cleanId.ifBlank { "YOUR_ACCOUNT_ID" }
                                val updatedUrl = if (state.baseUrl.contains(Regex("""/accounts/[^/]*(?=/|$)"""))) {
                                    state.baseUrl.replace(Regex("""/accounts/[^/]*(?=/|$)"""), "/accounts/$replacement")
                                } else {
                                    "https://api.cloudflare.com/client/v4/accounts/$replacement/ai/v1"
                                }
                                viewModel.updateBaseUrl(updatedUrl)
                            },
                            label = { Text(stringResource(R.string.provider_field_cloudflare_account_id)) },
                            supportingText = { Text(stringResource(R.string.provider_cloudflare_account_hint)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("cloudflare_account_id_input"),
                        )
                    }

                    OutlinedTextField(
                        value = state.apiKeyInput,
                        onValueChange = viewModel::updateApiKey,
                        label = { Text(stringResource(R.string.provider_field_api_key)) },
                        supportingText = { Text(stringResource(R.string.provider_api_key_hint)) },
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(
                                onClick = { showApiKey = !showApiKey },
                                modifier = Modifier.testTag("toggle_api_key_visibility"),
                            ) {
                                Icon(
                                    imageVector = if (showApiKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = stringResource(
                                        if (showApiKey) R.string.provider_hide_key else R.string.provider_show_key,
                                    ),
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("provider_api_key_input"),
                    )
                }
            }

            item(key = "protocol_mode") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.provider_field_protocol),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = state.protocol == ApiProtocol.OPENAI_COMPATIBLE,
                                onClick = { viewModel.updateProtocol(ApiProtocol.OPENAI_COMPATIBLE) },
                                label = { Text(stringResource(R.string.protocol_openai)) },
                                modifier = Modifier.testTag("protocol_openai"),
                            )
                            FilterChip(
                                selected = state.protocol == ApiProtocol.ANTHROPIC_COMPATIBLE,
                                onClick = { viewModel.updateProtocol(ApiProtocol.ANTHROPIC_COMPATIBLE) },
                                label = { Text(stringResource(R.string.protocol_anthropic)) },
                                modifier = Modifier.testTag("protocol_anthropic"),
                            )
                            FilterChip(
                                selected = state.protocol == ApiProtocol.AUTO,
                                onClick = { viewModel.updateProtocol(ApiProtocol.AUTO) },
                                label = { Text(stringResource(R.string.protocol_auto)) },
                                modifier = Modifier.testTag("protocol_auto"),
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.protocol_hint_openai),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(R.string.protocol_hint_anthropic),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(R.string.protocol_hint_tip),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            if (state.type.supportsOrgAndProject) {
                item(key = "org_project") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = state.organizationId,
                            onValueChange = viewModel::updateOrganizationId,
                            label = { Text(stringResource(R.string.provider_field_org_id)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = state.projectId,
                            onValueChange = viewModel::updateProjectId,
                            label = { Text(stringResource(R.string.provider_field_project_id)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item(key = "custom_headers") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.provider_headers_title),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = viewModel::addCustomHeader) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.provider_headers_add))
                            }
                        }
                        state.customHeaders.forEachIndexed { idx, header ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedTextField(
                                    value = header.name,
                                    onValueChange = { viewModel.updateCustomHeader(idx, it, header.value) },
                                    label = { Text(stringResource(R.string.provider_header_name)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = header.value,
                                    onValueChange = { viewModel.updateCustomHeader(idx, header.name, it) },
                                    label = { Text(stringResource(R.string.provider_header_value)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { viewModel.removeCustomHeader(idx) }) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = stringResource(R.string.action_delete),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "actions_and_check") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FilledTonalButton(
                            onClick = viewModel::testConnection,
                            enabled = !state.isTesting,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .heightIn(min = 48.dp)
                                .testTag("test_connection_button"),
                        ) {
                            if (state.isTesting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.provider_test_connection),
                                maxLines = 2,
                                textAlign = TextAlign.Center,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = viewModel::fetchModelsOnly,
                            enabled = !state.isFetchingModels,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .heightIn(min = 48.dp)
                                .testTag("fetch_models_button"),
                        ) {
                            if (state.isFetchingModels) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.provider_fetch_models),
                                maxLines = 2,
                                textAlign = TextAlign.Center,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    val checkTimeText = state.lastCheckedAt?.let {
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
                    } ?: stringResource(R.string.provider_never_checked)
                    Text(
                        text = stringResource(R.string.provider_last_check, checkTimeText) +
                            if (state.lastCheckMessage.isNotBlank()) " • ${state.lastCheckMessage}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    AnimatedVisibility(
                        visible = state.checkResult?.success == true,
                        enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                        exit = fadeOut(tween(140)) + shrinkVertically(tween(140)),
                    ) {
                        state.checkResult?.let { res ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = semantic.successContainer,
                                border = BorderStroke(1.dp, semantic.success.copy(alpha = 0.28f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = semantic.success,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = res.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }

                    state.checkError?.let { err ->
                        ErrorReportCard(
                            error = err,
                            onCopied = {
                                scope.launch { snackbarHostState.showSnackbar(copiedText) }
                            },
                        )
                        if (state.isExisting && state.models.isEmpty()) {
                            OutlinedButton(
                                onClick = { viewModel.saveAndFetchModels(onSaved = onBack) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("save_without_models_button"),
                            ) {
                                Text(stringResource(R.string.provider_save_without_models))
                            }
                        }
                    }
                }
            }

            item(key = "manual_model_entry") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.provider_manual_model_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (state.showManualModelPrompt) {
                            Text(
                                text = stringResource(R.string.provider_manual_model_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = state.manualModelInput,
                                onValueChange = viewModel::updateManualModelInput,
                                label = { Text(stringResource(R.string.provider_manual_model_hint)) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("manual_model_input"),
                            )
                            Button(
                                onClick = viewModel::addManualModel,
                                enabled = state.manualModelInput.isNotBlank(),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("add_manual_model_button"),
                            ) {
                                Text(stringResource(R.string.action_confirm))
                            }
                        }
                    }
                }
            }

            if (state.models.isNotEmpty()) {
                val selectedCount = state.models.count { it.isEnabled }
                val allSelected = selectedCount == state.models.size
                item(key = "loaded_models_header") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.provider_models_selected_header,
                                    selectedCount,
                                    state.models.size,
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { viewModel.setAllModelsSelected(!allSelected) },
                                modifier = Modifier.testTag("toggle_all_models_button"),
                            ) {
                                Text(
                                    stringResource(
                                        if (allSelected) {
                                            R.string.provider_models_deselect_all
                                        } else {
                                            R.string.provider_models_select_all
                                        },
                                    ),
                                )
                            }
                        }
                        if (state.models.size > 6) {
                            OutlinedTextField(
                                value = modelSearchQuery,
                                onValueChange = { modelSearchQuery = it },
                                placeholder = { Text(stringResource(R.string.models_search_hint)) },
                                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                items(filteredModels, key = { "cfg_model_${it.modelId}" }) { model ->
                    val isDefault = model.modelId == state.selectedModelId
                    val isChecked = model.isEnabled
                    val itemInteraction = remember { MutableInteractionSource() }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isChecked) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isChecked) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .pressScale(itemInteraction)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = itemInteraction,
                                indication = ripple(),
                            ) { viewModel.toggleModelSelection(model.modelId) }
                            .testTag("provider_model_row_${model.modelId}"),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 4.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { viewModel.toggleModelSelection(model.modelId) },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = model.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (model.displayName != model.modelId) {
                                    Text(
                                        text = model.modelId,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
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
                            IconButton(
                                onClick = { viewModel.selectDefaultModel(model.modelId) },
                            ) {
                                Icon(
                                    imageVector = if (isDefault) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                    contentDescription = stringResource(R.string.provider_model_default_cd),
                                    tint = if (isDefault) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
        }
    }
}
