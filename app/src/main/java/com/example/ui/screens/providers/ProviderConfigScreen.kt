package com.example.ui.screens.providers

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.model.ApiProtocol
import com.example.ui.components.CapabilityBadge
import com.example.ui.components.ErrorReportCard
import com.example.ui.components.ProviderLogoBadge
import com.example.ui.components.ProviderStatusChip
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
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedText = stringResource(R.string.error_copied)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.provider_config_title)) },
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
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "header_summary") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProviderLogoBadge(providerType = state.type, size = 48.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.type.defaultDisplayName,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(state.type.descriptionRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ProviderStatusChip(status = state.status)
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

                    OutlinedTextField(
                        value = state.baseUrl,
                        onValueChange = viewModel::updateBaseUrl,
                        label = { Text(stringResource(R.string.provider_field_base_url)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("provider_base_url_input"),
                    )

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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.provider_headers_title),
                            style = MaterialTheme.typography.titleSmall,
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
                                )
                            }
                        }
                    }
                }
            }

            item(key = "actions_and_check") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = viewModel::testConnection,
                            enabled = !state.isTesting,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("test_connection_button"),
                        ) {
                            if (state.isTesting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.provider_test_connection))
                        }
                        OutlinedButton(
                            onClick = viewModel::fetchModelsOnly,
                            enabled = !state.isFetchingModels,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("fetch_models_button"),
                        ) {
                            if (state.isFetchingModels) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.provider_fetch_models))
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

                    state.checkResult?.let { res ->
                        if (res.success) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = res.message,
                                        style = MaterialTheme.typography.bodyMedium,
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
                    }
                }
            }

            item(key = "manual_model_entry") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            modifier = Modifier.testTag("add_manual_model_button"),
                        ) {
                            Text(stringResource(R.string.action_confirm))
                        }
                    }

                    if (state.models.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.provider_models_loaded_count, state.models.size),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        state.models.take(20).forEach { model ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = model.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        model.capabilities.forEach { cap ->
                                            CapabilityBadge(capability = cap)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}
