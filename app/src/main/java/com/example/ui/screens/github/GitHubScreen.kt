package com.example.ui.screens.github

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.model.GitHubRepoSummary

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
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("github_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.github_title)) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "connection_card") {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Code,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
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
                                    tint = MaterialTheme.colorScheme.primary,
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
                                Text(
                                    text = state.maskedToken,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedButton(
                                    onClick = viewModel::disconnect,
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                    modifier = Modifier.testTag("github_disconnect_button"),
                                ) {
                                    Text(stringResource(R.string.github_disconnect_button))
                                }
                            }
                        }

                        state.errorMessage?.let { err ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(10.dp),
                                )
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

                            HorizontalDivider()

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
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("github_repo_filter_input"),
                        )

                        if (state.isLoadingRepos) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                items(state.filteredRepos, key = { it.id }) { repo ->
                    val isDefault = state.settings.defaultRepoFullName.equals(repo.fullName, ignoreCase = true)
                    RepoCard(
                        repo = repo,
                        isDefault = isDefault,
                        onSetDefault = { viewModel.selectDefaultRepo(repo.fullName) },
                        onInspect = { viewModel.openInspector(repo) },
                    )
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
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        border = if (isDefault) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("github_repo_card_${repo.name}"),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (repo.isPrivate) Icons.Outlined.Lock else Icons.Outlined.Public,
                    contentDescription = null,
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
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = repo.defaultBranch,
                        style = MaterialTheme.typography.labelSmall,
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
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isDefault) {
                    FilledTonalButton(onClick = {}, enabled = false) {
                        Text(stringResource(R.string.github_default_badge))
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
        title = { Text(stringResource(R.string.github_create_repo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.github_repo_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.github_repo_desc_label)) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { isPrivate = !isPrivate },
                ) {
                    Checkbox(checked = isPrivate, onCheckedChange = { isPrivate = it })
                    Text(stringResource(R.string.github_repo_private))
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
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = inspector.repo.fullName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = inspector.selectedBranch,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                FilledTonalButton(onClick = onSetAsDefaultRepo) {
                    Text(stringResource(R.string.github_set_default))
                }
            }

            if (inspector.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            inspector.error?.let { err ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            val tabs = listOf(
                stringResource(R.string.github_files),
                stringResource(R.string.github_branches),
                stringResource(R.string.github_issues),
                stringResource(R.string.github_prs),
            )
            SecondaryTabRow(selectedTabIndex = selectedTab) {
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
                        val filePreview = inspector.previewFile
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
                                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.cd_back))
                                    }
                                    Text(
                                        text = filePreview.path,
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    Text(
                                        text = filePreview.content,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier
                                            .verticalScroll(rememberScrollState())
                                            .horizontalScroll(rememberScrollState())
                                            .padding(12.dp),
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(bottom = 24.dp),
                            ) {
                                if (inspector.currentPath.isNotBlank()) {
                                    item(key = "parent_dir") {
                                        val parent = inspector.currentPath.substringBeforeLast('/', "")
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onOpenDirectory(parent) },
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            ) {
                                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                                                Text("../ (${inspector.currentPath})", style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                    }
                                }
                                items(inspector.entries, key = { it.path }) { entry ->
                                    val isDir = entry.type == "dir"
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (isDir) onOpenDirectory(entry.path) else onOpenFile(entry.path)
                                            },
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 24.dp),
                        ) {
                            items(inspector.issues, key = { it.number }) { issue ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        Icon(Icons.Outlined.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Column {
                                            Text("#${issue.number} ${issue.title}", style = MaterialTheme.typography.bodyMedium)
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

                    3 -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 24.dp),
                        ) {
                            items(inspector.pullRequests, key = { it.number }) { pr ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        Icon(Icons.AutoMirrored.Outlined.CallSplit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Column {
                                            Text("#${pr.number} ${pr.title}", style = MaterialTheme.typography.bodyMedium)
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

    if (showNewBranchDialog) {
        var branchName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewBranchDialog = false },
            title = { Text(stringResource(R.string.github_create_branch)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.github_from_branch_label, inspector.selectedBranch),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = branchName,
                        onValueChange = { branchName = it },
                        label = { Text(stringResource(R.string.github_branch_name_label)) },
                        singleLine = true,
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
