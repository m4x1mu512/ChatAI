package com.example.ui.screens.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.R
import com.example.core.model.Attachment
import com.example.core.model.AttachmentType
import com.example.core.model.ChatMessage
import com.example.core.model.GeneratedMedia
import com.example.core.model.GeneratedMediaType
import com.example.core.model.GitHubBranchInfo
import com.example.core.model.GitHubPendingApproval
import com.example.core.model.GitHubRepoSummary
import com.example.core.model.MessageRole
import com.example.core.model.MessageStatus
import com.example.ui.components.ErrorReportCard
import com.example.ui.components.InlineVideoPlayer
import com.example.ui.components.MarkdownText
import com.example.ui.components.ProviderLogoBadge
import com.example.ui.components.SkeletonShimmerBlock
import com.example.ui.screens.models.ModelPickerSheet
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onOpenImageViewer: (imageUris: List<String>, initialIndex: Int) -> Unit,
    onOpenVideoViewer: (videoUri: String) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenGitHub: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var showModelPicker by remember { mutableStateOf(false) }
    var showGitHubSheet by remember { mutableStateOf(false) }
    var attachMenuExpanded by remember { mutableStateOf(false) }
    var pendingSaveMedia by remember { mutableStateOf<GeneratedMedia?>(null) }

    val copiedMessage = stringResource(R.string.chat_copied)
    val errorCopiedMessage = stringResource(R.string.error_copied)
    val savedSuccessText = stringResource(R.string.media_saved_success)

    // Photo Picker (zero-permission)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        uri?.let { viewModel.attachImageFromUri(it) }
    }

    // Document Picker (SAF - zero-permission)
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let { viewModel.attachDocumentFromUri(it) }
    }

    // Camera capture
    val cameraCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        viewModel.onCameraCaptured(success)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val uri = viewModel.prepareCameraOutputUri()
            cameraCaptureLauncher.launch(uri)
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.chat_camera_permission_rationale))
            }
        }
    }

    // Legacy Android 8-9 write permission only when saving media to MediaStore
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val media = pendingSaveMedia
        pendingSaveMedia = null
        if (granted && media != null) {
            viewModel.saveMediaToGallery(media) { res ->
                scope.launch {
                    val msg = res.fold(
                        onSuccess = { savedSuccessText },
                        onFailure = { context.getString(R.string.media_saved_error, it.message.orEmpty()) },
                    )
                    snackbarHostState.showSnackbar(msg)
                }
            }
        }
    }

    fun triggerSaveMedia(media: GeneratedMedia) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSaveMedia = media
            legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.saveMediaToGallery(media) { res ->
                scope.launch {
                    val msg = res.fold(
                        onSuccess = { savedSuccessText },
                        onFailure = { context.getString(R.string.media_saved_error, it.message.orEmpty()) },
                    )
                    snackbarHostState.showSnackbar(msg)
                }
            }
        }
    }

    val allChatImageUris = remember(state.messages) {
        buildList {
            for (m in state.messages) {
                for (att in m.attachments) {
                    if (att.type == AttachmentType.IMAGE && att.localUri.isNotBlank()) add(att.localUri)
                }
                for (gm in m.generatedMedia) {
                    if (gm.type == GeneratedMediaType.IMAGE) {
                        val u = gm.localUri.ifBlank { gm.remoteUrl }
                        if (u.isNotBlank()) add(u)
                    }
                }
            }
        }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                            )
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            text = state.chat?.title ?: stringResource(R.string.chats_title),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        state.activeProvider?.let { provider ->
                            Text(
                                text = provider.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    val isUnselected = state.activeModelId.isNullOrBlank()
                    val pillContentColor = if (isUnselected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (isUnselected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        border = BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                        ),
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .heightIn(min = 40.dp)
                            .widthIn(max = 190.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                viewModel.syncConnectedProviderModels()
                                showModelPicker = true
                            }
                            .testTag("model_selector_pill"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            state.activeProvider?.let {
                                ProviderLogoBadge(providerType = it.type, size = 18.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = state.activeModelId ?: stringResource(R.string.chat_select_model),
                                style = MaterialTheme.typography.labelMedium,
                                color = pillContentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = Icons.Outlined.ExpandMore,
                                contentDescription = stringResource(R.string.chat_select_model),
                                tint = pillContentColor,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.isLoadingAttachment) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                state.unsupportedAttachmentWarning?.let { badType ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(
                                    R.string.chat_attachment_unsupported_warning,
                                    state.activeModelId.orEmpty(),
                                    badType.name.lowercase(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { showModelPicker = true }) {
                                Text(stringResource(R.string.chat_choose_compatible_model))
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = state.detectedUrls.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.detectedUrls.forEach { url ->
                            AssistChip(
                                onClick = { viewModel.attachWebLink(url) },
                                label = {
                                    Text(
                                        text = "${stringResource(R.string.chat_add_as_attachment)}: ${url.take(32)}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Outlined.AddLink, contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                                modifier = Modifier.testTag("add_detected_url_chip"),
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = state.stagedAttachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.stagedAttachments.forEach { att ->
                            StagedAttachmentChip(
                                attachment = att,
                                onRemove = { viewModel.removeStagedAttachment(att.id) },
                            )
                        }
                    }
                }

                state.editingMessage?.let {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.chat_edit_message),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            IconButton(onClick = viewModel::cancelEditMessage, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cd_close), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                state.pendingGitHubApproval?.let { approval ->
                    GitHubApprovalBannerCard(
                        approval = approval,
                        onApprove = viewModel::approvePendingGitHubWrite,
                        onReject = viewModel::rejectPendingGitHubWrite,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (state.activeModelId.isNullOrBlank()) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                viewModel.syncConnectedProviderModels()
                                showModelPicker = true
                            }
                            .testTag("composer_model_selector"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            state.activeProvider?.let {
                                ProviderLogoBadge(providerType = it.type, size = 16.dp)
                            } ?: Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = buildString {
                                    state.activeProvider?.let {
                                        append(it.displayName)
                                        append(" • ")
                                    }
                                    append(state.activeModelId ?: stringResource(R.string.chat_select_model))
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 220.dp),
                            )
                            Icon(
                                imageVector = Icons.Outlined.ExpandMore,
                                contentDescription = stringResource(R.string.chat_switch_model),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    val ghConnected = state.gitHubSettings.isConnected
                    val ghActive = state.isGitHubToolsActiveInChat
                    val ghChipText = when {
                        !ghConnected -> stringResource(R.string.github_title)
                        !state.effectiveGitHubRepo.isNullOrBlank() -> {
                            val br = state.effectiveGitHubBranch
                            if (!br.isNullOrBlank()) "${state.effectiveGitHubRepo} ($br)" else state.effectiveGitHubRepo.orEmpty()
                        }
                        else -> stringResource(R.string.github_chat_select_repo)
                    }
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (ghConnected && ghActive) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                if (ghConnected) {
                                    viewModel.loadGitHubPickerData()
                                    showGitHubSheet = true
                                } else {
                                    onOpenGitHub()
                                }
                            }
                            .testTag("github_chat_chip"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Code,
                                contentDescription = stringResource(R.string.github_title),
                                modifier = Modifier.size(15.dp),
                                tint = if (ghConnected && ghActive) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                text = ghChipText,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (ghConnected && ghActive) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 200.dp),
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box {
                        IconButton(
                            onClick = { attachMenuExpanded = true },
                            modifier = Modifier.testTag("attach_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AttachFile,
                                contentDescription = stringResource(R.string.chat_attach),
                            )
                        }
                        DropdownMenu(
                            expanded = attachMenuExpanded,
                            onDismissRequest = { attachMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_attach_gallery)) },
                                leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                                onClick = {
                                    attachMenuExpanded = false
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_attach_camera)) },
                                leadingIcon = { Icon(Icons.Outlined.CameraAlt, contentDescription = null) },
                                onClick = {
                                    attachMenuExpanded = false
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                        val uri = viewModel.prepareCameraOutputUri()
                                        cameraCaptureLauncher.launch(uri)
                                    } else {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_attach_document)) },
                                leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                                onClick = {
                                    attachMenuExpanded = false
                                    documentPickerLauncher.launch(
                                        arrayOf("application/pdf", "text/*", "application/json", "application/octet-stream"),
                                    )
                                },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = state.inputText,
                        onValueChange = viewModel::updateInputText,
                        placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                        maxLines = 5,
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_message_input"),
                    )

                    if (state.isGenerating) {
                        FilledIconButton(
                            onClick = viewModel::stopGeneration,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("stop_generation_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Stop,
                                contentDescription = stringResource(R.string.chat_stop),
                            )
                        }
                    } else {
                        val canSend = (state.inputText.isNotBlank() || state.stagedAttachments.isNotEmpty()) &&
                            state.activeProvider != null && !state.activeModelId.isNullOrBlank()
                        FilledIconButton(
                            onClick = viewModel::sendMessage,
                            enabled = canSend,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("send_message_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Send,
                                contentDescription = stringResource(R.string.chat_send),
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (state.activeProvider == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.chats_no_provider_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.chats_no_provider_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        TextButton(onClick = onOpenProviders) {
                            Text(stringResource(R.string.providers_onboarding_title))
                        }
                    }
                }
            } else if (state.messages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.chats_empty_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.chats_empty_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        FilledTonalButton(
                            onClick = {
                                viewModel.syncConnectedProviderModels()
                                showModelPicker = true
                            },
                            modifier = Modifier.testTag("empty_chat_select_model_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = state.activeModelId ?: stringResource(R.string.chat_select_model),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Outlined.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.messages, key = { it.id }) { msg ->
                        ChatMessageBubble(
                            message = msg,
                            onCopyText = {
                                clipboard.setText(AnnotatedString(msg.content))
                                scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                            },
                            onRetry = { viewModel.retryAssistantMessage(msg) },
                            onEditUser = { viewModel.startEditUserMessage(msg) },
                            onDelete = { viewModel.deleteMessage(msg.id) },
                            onImageClick = { uri ->
                                val idx = allChatImageUris.indexOf(uri).coerceAtLeast(0)
                                onOpenImageViewer(allChatImageUris.ifEmpty { listOf(uri) }, idx)
                            },
                            onOpenDocument = { att ->
                                runCatching {
                                    val intent = viewModel.createOpenDocumentIntent(att)
                                    context.startActivity(intent)
                                }.onFailure {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(it.message ?: "No viewer found")
                                    }
                                }
                            },
                            onOpenUrl = { url ->
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            },
                            onSaveMedia = { gm -> triggerSaveMedia(gm) },
                            onShareMedia = { gm ->
                                context.startActivity(
                                    viewModel.createShareMediaIntent(gm, context.getString(R.string.action_share)),
                                )
                            },
                            onOpenVideo = { gm ->
                                val u = gm.localUri.ifBlank { gm.remoteUrl }
                                if (u.isNotBlank()) onOpenVideoViewer(u)
                            },
                            onCopyLink = { url ->
                                clipboard.setText(AnnotatedString(url))
                                scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                            },
                            onErrorCopied = {
                                scope.launch { snackbarHostState.showSnackbar(errorCopiedMessage) }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(
            providers = state.providers,
            models = state.models,
            selectedProviderId = state.activeProvider?.id,
            selectedModelId = state.activeModelId,
            refreshingProviderIds = state.refreshingProviderIds,
            onSelectModel = viewModel::selectModel,
            onAddManualModel = viewModel::addManualModel,
            onRefreshModels = viewModel::refreshProviderModels,
            onOpenProviders = onOpenProviders,
            onDismiss = { showModelPicker = false },
        )
    }

    if (showGitHubSheet) {
        ChatGitHubPickerSheet(
            repos = state.gitHubRepos,
            branches = state.gitHubBranches,
            selectedRepo = state.effectiveGitHubRepo,
            selectedBranch = state.effectiveGitHubBranch,
            toolsEnabledInChat = state.chat?.githubToolsEnabled ?: true,
            isLoading = state.isLoadingGitHubRepos,
            onToggleTools = viewModel::toggleChatGitHubTools,
            onSelectRepo = { repo, branch -> viewModel.selectChatGitHubRepo(repo, branch) },
            onSelectBranch = { branch -> viewModel.selectChatGitHubRepo(state.effectiveGitHubRepo, branch) },
            onOpenFullGitHub = {
                showGitHubSheet = false
                onOpenGitHub()
            },
            onDismiss = { showGitHubSheet = false },
        )
    }
}

@Composable
private fun StagedAttachmentChip(
    attachment: Attachment,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (attachment.type) {
                AttachmentType.IMAGE -> {
                    AsyncImage(
                        model = attachment.localUri,
                        contentDescription = attachment.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
                AttachmentType.DOCUMENT -> Icon(
                    imageVector = if (attachment.mimeType.contains("pdf")) Icons.Outlined.PictureAsPdf else Icons.AutoMirrored.Outlined.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                AttachmentType.TEXT_FILE -> Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                AttachmentType.WEB_LINK -> Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = attachment.displayName,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 130.dp),
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(22.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cd_close), modifier = Modifier.size(14.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatMessageBubble(
    message: ChatMessage,
    onCopyText: () -> Unit,
    onRetry: () -> Unit,
    onEditUser: () -> Unit,
    onDelete: () -> Unit,
    onImageClick: (String) -> Unit,
    onOpenDocument: (Attachment) -> Unit,
    onOpenUrl: (String) -> Unit,
    onSaveMedia: (GeneratedMedia) -> Unit,
    onShareMedia: (GeneratedMedia) -> Unit,
    onOpenVideo: (GeneratedMedia) -> Unit,
    onCopyLink: (String) -> Unit,
    onErrorCopied: () -> Unit,
) {
    val isUser = message.role == MessageRole.USER
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (!isUser && (message.providerName.isNotBlank() || message.modelId.isNotBlank())) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
            ) {
                Text(
                    text = listOf(message.providerName, message.modelId)
                        .filter { it.isNotBlank() }
                        .joinToString(" • "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MessageStatusLabel(status = message.status)
            }
        }

        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            tonalElevation = if (isUser) 0.dp else 1.dp,
            modifier = Modifier.widthIn(max = 600.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Attachments in bubble
                if (message.attachments.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        message.attachments.forEach { att ->
                            when (att.type) {
                                AttachmentType.IMAGE -> {
                                    AsyncImage(
                                        model = att.localUri,
                                        contentDescription = att.displayName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(128.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { onImageClick(att.localUri) }
                                            .testTag("message_image_thumbnail"),
                                    )
                                }
                                AttachmentType.DOCUMENT, AttachmentType.TEXT_FILE -> {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { onOpenDocument(att) }
                                            .testTag("message_document_card"),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Icon(
                                                imageVector = if (att.mimeType.contains("pdf")) Icons.Outlined.PictureAsPdf else Icons.Outlined.Description,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                            Column {
                                                Text(
                                                    text = att.displayName,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = Formatter.formatShortFileSize(context, att.sizeBytes),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                                contentDescription = stringResource(R.string.action_open),
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                                AttachmentType.WEB_LINK -> {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { onOpenUrl(att.url) }
                                            .testTag("message_link_card"),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Link,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            )
                                            Column {
                                                Text(
                                                    text = att.pageTitle.ifBlank { att.url },
                                                    style = MaterialTheme.typography.labelMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = att.url,
                                                    style = MaterialTheme.typography.labelSmall,
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

                // Reasoning collapsible / subtle block if present
                if (message.reasoningContent.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ) {
                        Text(
                            text = message.reasoningContent,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }

                // Loading skeleton before first token arrives
                if (!isUser && message.content.isBlank() && message.generatedMedia.isEmpty() &&
                    (message.status == MessageStatus.SENDING || message.status == MessageStatus.GENERATING)
                ) {
                    SkeletonShimmerBlock()
                }

                if (message.content.isNotBlank()) {
                    if (isUser) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        MarkdownText(
                            markdown = message.content,
                            onCopied = onCopyText,
                        )
                    }
                }

                // Generated Media (Image / Video)
                message.generatedMedia.forEach { gm ->
                    val mediaSource = gm.localUri.ifBlank { gm.remoteUrl }
                    if (gm.type == GeneratedMediaType.IMAGE && mediaSource.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            AsyncImage(
                                model = mediaSource,
                                contentDescription = gm.prompt,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onImageClick(mediaSource) }
                                    .testTag("generated_image_card"),
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { onSaveMedia(gm) }) {
                                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.media_save))
                                }
                                TextButton(onClick = { onShareMedia(gm) }) {
                                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.action_share))
                                }
                                if (gm.remoteUrl.isNotBlank()) {
                                    TextButton(onClick = { onCopyLink(gm.remoteUrl) }) {
                                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.media_copy_link))
                                    }
                                }
                                TextButton(onClick = onRetry) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.action_retry))
                                }
                            }
                        }
                    } else if (gm.type == GeneratedMediaType.VIDEO && mediaSource.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            InlineVideoPlayer(mediaUri = mediaSource)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { onOpenVideo(gm) }) {
                                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.action_open))
                                }
                                TextButton(onClick = { onSaveMedia(gm) }) {
                                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.media_save))
                                }
                                TextButton(onClick = { onShareMedia(gm) }) {
                                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.action_share))
                                }
                                TextButton(onClick = onRetry) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.action_retry))
                                }
                            }
                        }
                    }
                }

                // Error card
                message.error?.let { err ->
                    ErrorReportCard(
                        error = err,
                        onCopied = onErrorCopied,
                    )
                }
            }
        }

        // Message action row
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (message.content.isNotBlank()) {
                IconButton(onClick = onCopyText, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.chat_copy_message),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isUser) {
                IconButton(onClick = onEditUser, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.chat_edit_message),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (message.status == MessageStatus.ERROR || message.status == MessageStatus.CANCELLED || message.status == MessageStatus.COMPLETED) {
                IconButton(onClick = onRetry, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.action_retry),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.action_delete),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MessageStatusLabel(status: MessageStatus) {
    val label = when (status) {
        MessageStatus.SENDING -> stringResource(R.string.chat_status_sending)
        MessageStatus.GENERATING -> stringResource(R.string.chat_status_generating)
        MessageStatus.COMPLETED -> stringResource(R.string.chat_status_completed)
        MessageStatus.ERROR -> stringResource(R.string.chat_status_error)
        MessageStatus.CANCELLED -> stringResource(R.string.chat_status_cancelled)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (status == MessageStatus.SENDING || status == MessageStatus.GENERATING) {
            CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
        }
        Text(
            text = "• $label",
            style = MaterialTheme.typography.labelSmall,
            color = if (status == MessageStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GitHubApprovalBannerCard(
    approval: GitHubPendingApproval,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("github_approval_card"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = stringResource(R.string.github_approval_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            approval.actions.forEach { action ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = action.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        text = action.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                    )
                    if (action.previewContent.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 120.dp),
                        ) {
                            Text(
                                text = action.previewContent,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .verticalScroll(rememberScrollState())
                                    .horizontalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.testTag("github_approval_reject"),
                ) {
                    Text(stringResource(R.string.github_approval_reject))
                }
                Button(
                    onClick = onApprove,
                    modifier = Modifier.testTag("github_approval_confirm"),
                ) {
                    Text(stringResource(R.string.github_approval_approve))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatGitHubPickerSheet(
    repos: List<GitHubRepoSummary>,
    branches: List<GitHubBranchInfo>,
    selectedRepo: String?,
    selectedBranch: String?,
    toolsEnabledInChat: Boolean,
    isLoading: Boolean,
    onToggleTools: (Boolean) -> Unit,
    onSelectRepo: (repoFullName: String?, branch: String?) -> Unit,
    onSelectBranch: (String) -> Unit,
    onOpenFullGitHub: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var search by remember { mutableStateOf("") }
    val filtered = remember(repos, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) repos else repos.filter { it.fullName.lowercase().contains(q) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.github_chat_repo_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onOpenFullGitHub) {
                    Text(stringResource(R.string.github_configure))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.github_chat_tools_toggle),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = toolsEnabledInChat,
                    onCheckedChange = onToggleTools,
                )
            }

            if (branches.isNotEmpty() && !selectedRepo.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.github_chat_branch_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    branches.forEach { b ->
                        FilterChip(
                            selected = b.name == selectedBranch,
                            onClick = { onSelectBranch(b.name) },
                            label = { Text(b.name) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text(stringResource(R.string.github_search_repos_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (!selectedRepo.isNullOrBlank()) {
                TextButton(
                    onClick = { onSelectRepo(null, null) },
                ) {
                    Text(stringResource(R.string.github_chat_clear_repo))
                }
            }

            HorizontalDivider()

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(filtered, key = { it.id }) { repo ->
                    val isSelected = selectedRepo.equals(repo.fullName, ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectRepo(repo.fullName, repo.defaultBranch)
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = repo.fullName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                Text(
                                    text = repo.defaultBranch,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
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
