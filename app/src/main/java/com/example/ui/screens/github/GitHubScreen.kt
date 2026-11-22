package com.example.ui.screens.github

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.model.GitHubRepoSummary
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.pressScale
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.semanticColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GitHubScreen(
    viewModel: GitHubViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showToken by remember { mutableStateOf(false) }
    var showCreateRepoDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("github_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.github_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    if (state.settings.isConnected) {
                        IconButton(
                            onClick = viewModel::loadRepos,
                            modifier = Modifier.testTag("github_refresh_repos_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.github_refresh),
                            )
                        }
                    }
                },
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
                item(key = "connection_card") {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                                    modifier = Modifier.size(38.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Outlined.Code,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (state.settings.isConnected) {
                                            stringResource(R.string.github_connected_as, state.settings.username)
                                        } else {
                                            stringResource(R.string.github_not_connected)
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    if (state.settings.isConnected && state.settings.scopes.isNotBlank()) {
                                        Text(
                                            text = stringResource(R.string.github_scopes_label, state.settings.scopes),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (state.settings.isConnected) {
                                    Icon(
                                        imageVector = Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.semanticColors.success,
                                    )
                                }
                            }

                            if (!state.settings.isConnected) {
                                OutlinedTextField(
                                    value = state.tokenInput,
                                    onValueChange = viewModel::updateTokenInput,
                                    label = { Text(stringResource(R.string.github_token_label)) },
                                    supportingText = { Text(stringResource(R.string.github_token_hint)) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { showToken = !showToken }) {
                                            Icon(
                                                imageVector = if (showToken) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                                contentDescription = null,
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("github_token_input"),
                                )
                                Button(
                                    onClick = viewModel::connectWithToken,
                                    enabled = state.tokenInput.isNotBlank() && !state.isConnecting,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("github_connect_button"),
                                ) {
                                    if (state.isConnecting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(stringResource(R.string.github_connect_button))
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    ) {
                                        Text(
                                            text = state.maskedToken,
                                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = viewModel::disconnect,
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error,
                                        ),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                                        modifier = Modifier.testTag("github_disconnect_button"),
                                    ) {
                                        Text(stringResource(R.string.github_disconnect_button))
                                    }
                                }
                            }

                            AnimatedVisibility(visible = state.errorMessage != null) {
                                state.errorMessage?.let { err ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.28f)),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            text = err,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.padding(12.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (state.settings.isConnected) {
                    item(key = "agent_behavior") {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text(
                                            text = stringResource(R.string.github_tools_enabled),
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = stringResource(R.string.github_tools_enabled_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = state.settings.toolsEnabled,
                                        onCheckedChange = viewModel::setToolsEnabled,
                                        modifier = Modifier.testTag("github_tools_switch"),
                                    )
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text(
                                            text = stringResource(R.string.github_confirm_writes),
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = stringResource(R.string.github_confirm_writes_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = state.settings.confirmWritesBeforeExecute,
                                        onCheckedChange = viewModel::setConfirmWrites,
                                        modifier = Modifier.testTag("github_confirm_writes_switch"),
                                    )
                                }
                            }
                        }
                    }

                    item(key = "repos_header") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.github_repositories_title, state.repos.size),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                FilledTonalButton(
                                    onClick = { showCreateRepoDialog = true },
                                    modifier = Modifier.testTag("github_new_repo_button"),
                                ) {
                                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.github_create_repo))
                                }
                            }

                            OutlinedTextField(
                                value = state.repoFilter,
                                onValueChange = viewModel::updateRepoFilter,
                                placeholder = { Text(stringResource(R.string.github_search_repos_hint)) },
                                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (state.repoFilter.isNotEmpty()) {
                                        IconButton(onClick = { viewModel.updateRepoFilter("") }) {
                                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cd_close))
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("github_repo_filter_input"),
                            )

                            AnimatedVisibility(visible = state.isLoadingRepos) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }

                    if (state.filteredRepos.isEmpty() && !state.isLoadingRepos) {
                        item(key = "repos_empty") {
                            EmptyStatePanel(
                                icon = Icons.Outlined.Code,
                                title = stringResource(R.string.github_repos_empty),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        items(state.filteredRepos, key = { it.id }) { repo ->
                            val isDefault = state.settings.defaultRepoFullName.equals(repo.fullName, ignoreCase = true)
                            RepoCard(
                                repo = repo,
                                isDefault = isDefault,
                                onSetDefault = { viewModel.selectDefaultRepo(repo.fullName) },
                                onInspect = { viewModel.openInspector(repo) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateRepoDialog) {
        CreateRepositoryDialog(
            onDismiss = { showCreateRepoDialog = false },
            onCreate = { name, desc, isPrivate ->
                showCreateRepoDialog = false
                viewModel.createRepository(name, desc, isPrivate)
            },
        )
    }

    state.inspector?.let { insp ->
        RepoInspectorSheet(
            inspector = insp,
            onDismiss = viewModel::closeInspector,
            onSelectBranch = viewModel::switchInspectorBranch,
            onOpenDirectory = viewModel::openInspectorDirectory,
            onOpenFile = viewModel::openInspectorFile,
            onCloseFilePreview = viewModel::closeFilePreview,
            onCreateBranch = viewModel::createBranchInInspector,
            onSetAsDefaultRepo = {
                viewModel.selectDefaultRepo(insp.repo.fullName)
                viewModel.closeInspector()
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepoCard(
    repo: GitHubRepoSummary,
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onInspect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = MaterialTheme.semanticColors
    val interaction = remember { MutableInteractionSource() }
    Card(
        shape = RoundedCornerShape(16.dp),
        border = if (isDefault) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                onClick = onInspect,
            )
            .testTag("github_repo_card_${repo.name}"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (repo.isPrivate) Icons.Outlined.Lock else Icons.Outlined.Public,
                    contentDescription = stringResource(
                        if (repo.isPrivate) R.string.github_private_badge else R.string.github_public_badge,
                    ),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = repo.fullName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(
                        text = repo.defaultBranch,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            if (repo.description.isNotBlank()) {
                Text(
                    text = repo.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isDefault) {
                    Surface(
                        shape = CircleShape,
                        color = semantic.successContainer,
                        border = BorderStroke(1.dp, semantic.success.copy(alpha = 0.25f)),
                        modifier = Modifier.align(Alignment.CenterVertically),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = semantic.success,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = stringResource(R.string.github_default_badge),
                                style = MaterialTheme.typography.labelLarge,
                                color = semantic.success,
                            )
                        }
                    }
                } else {
                    OutlinedButton(onClick = onSetDefault) {
                        Text(stringResource(R.string.github_set_default))
                    }
                }
                FilledTonalButton(onClick = onInspect) {
                    Text(stringResource(R.string.github_inspect_repo))
                }
            }
        }
    }
}

@Composable
private fun CreateRepositoryDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, isPrivate: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.github_create_repo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.github_repo_name_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.github_repo_desc_label)) },
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { isPrivate = !isPrivate },
                ) {
                    Checkbox(checked = isPrivate, onCheckedChange = { isPrivate = it })
                    Text(
                        text = stringResource(R.string.github_repo_private),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, description, isPrivate) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoInspectorSheet(
    inspector: RepoInspectorState,
    onDismiss: () -> Unit,
    onSelectBranch: (String) -> Unit,
    onOpenDirectory: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onCloseFilePreview: () -> Unit,
    onCreateBranch: (String) -> Unit,
    onSetAsDefaultRepo: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableIntStateOf(0) }
    var showNewBranchDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = inspector.repo.fullName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = inspector.selectedBranch,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                FilledTonalButton(onClick = onSetAsDefaultRepo) {
                    Text(stringResource(R.string.github_set_default))
                }
            }

            AnimatedVisibility(visible = inspector.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            AnimatedVisibility(visible = inspector.error != null) {
                inspector.error?.let { err ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.28f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            val tabs = listOf(
                stringResource(R.string.github_files),
                stringResource(R.string.github_branches),
                stringResource(R.string.github_issues),
                stringResource(R.string.github_prs),
            )
            SecondaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                tabs.forEachIndexed { idx, title ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                    0 -> {
                        AnimatedContent(
                            targetState = inspector.previewFile,
                            transitionSpec = {
                                fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                            },
                            label = "file_preview_transition",
                        ) { filePreview ->
                            if (filePreview != null) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        TextButton(onClick = onCloseFilePreview) {
                                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(stringResource(R.string.cd_back))
                                        }
                                        Text(
                                            text = filePreview.path,
                                            style = MaterialTheme.typography.labelLarge.copy(fontFamily = MonoFontFamily),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier.fillMaxSize(),
                                    ) {
                                        Text(
                                            text = filePreview.content,
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier
                                                .verticalScroll(rememberScrollState())
                                                .horizontalScroll(rememberScrollState())
                                                .padding(12.dp),
                                        )
                                    }
                                }
                            } else if (inspector.entries.isEmpty() && inspector.currentPath.isBlank() && !inspector.isLoading) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    EmptyStatePanel(
                                        icon = Icons.Outlined.Folder,
                                        title = stringResource(R.string.github_files_empty),
                                    )
                                }
                            } else {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(bottom = 24.dp),
                                ) {
                                    if (inspector.currentPath.isNotBlank()) {
                                        item(key = "parent_dir") {
                                            val parent = inspector.currentPath.substringBeforeLast('/', "")
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable { onOpenDirectory(parent) },
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                ) {
                                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                                                    Text(
                                                        text = "../ (${inspector.currentPath})",
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (inspector.entries.isEmpty() && !inspector.isLoading) {
                                        item(key = "dir_empty") {
                                            EmptyStatePanel(
                                                icon = Icons.Outlined.Folder,
                                                title = stringResource(R.string.github_files_empty),
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                    } else {
                                        items(inspector.entries, key = { it.path }) { entry ->
                                            val isDir = entry.type == "dir"
                                            Surface(
                                                shape = RoundedCornerShape(12.dp),
                                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .animateItem()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable {
                                                        if (isDir) onOpenDirectory(entry.path) else onOpenFile(entry.path)
                                                    },
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                ) {
                                                    Icon(
                                                        imageVector = if (isDir) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,
                                                        contentDescription = null,
                                                        tint = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                    Text(
                                                        text = entry.name,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        modifier = Modifier.weight(1f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 24.dp),
                        ) {
                            item(key = "new_branch_btn") {
                                FilledTonalButton(
                                    onClick = { showNewBranchDialog = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.github_create_branch))
                                }
                            }
                            items(inspector.branches, key = { it.name }) { branch ->
                                val selected = branch.name == inspector.selectedBranch
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSelectBranch(branch.name) },
                                    label = { Text(branch.name) },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Outlined.CallSplit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                    2 -> {
                        if (inspector.issues.isEmpty() && !inspector.isLoading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                EmptyStatePanel(
                                    icon = Icons.Outlined.BugReport,
                                    title = stringResource(R.string.github_issues_empty),
                                )
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 24.dp),
                            ) {
                                items(inspector.issues, key = { it.number }) { issue ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .animateItem(),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.Top,
                                        ) {
                                            Icon(Icons.Outlined.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            Column {
                                                Text("#${issue.number} ${issue.title}", style = MaterialTheme.typography.bodyMedium)
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "${issue.state} • @${issue.author}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    3 -> {
                        if (inspector.pullRequests.isEmpty() && !inspector.isLoading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                EmptyStatePanel(
                                    icon = Icons.AutoMirrored.Outlined.CallSplit,
                                    title = stringResource(R.string.github_prs_empty),
                                )
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 24.dp),
                            ) {
                                items(inspector.pullRequests, key = { it.number }) { pr ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .animateItem(),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.Top,
                                        ) {
                                            Icon(Icons.AutoMirrored.Outlined.CallSplit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            Column {
                                                Text("#${pr.number} ${pr.title}", style = MaterialTheme.typography.bodyMedium)
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "${pr.headBranch} → ${pr.baseBranch} • @${pr.author}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    }

    if (showNewBranchDialog) {
        var branchName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewBranchDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.github_create_branch)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.github_from_branch_label, inspector.selectedBranch),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = branchName,
                        onValueChange = { branchName = it },
                        label = { Text(stringResource(R.string.github_branch_name_label)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNewBranchDialog = false
                        onCreateBranch(branchName)
                    },
                    enabled = branchName.isNotBlank(),
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewBranchDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
