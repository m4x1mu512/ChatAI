package com.example.ui.screens.media

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.R
import com.example.core.model.GeneratedMediaType
import com.example.core.util.MediaStoreSaver
import com.example.ui.components.InlineVideoPlayer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    uris: List<String>,
    initialIndex: Int,
    isVideo: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mediaStoreSaver = remember { MediaStoreSaver(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val savedSuccessText = stringResource(R.string.media_saved_success)

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (uris.size - 1).coerceAtLeast(0)),
        pageCount = { uris.size.coerceAtLeast(1) },
    )

    val currentUri = uris.getOrElse(pagerState.currentPage) { uris.firstOrNull().orEmpty() }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && currentUri.isNotBlank()) {
            scope.launch {
                val res = mediaStoreSaver.saveToGallery(
                    currentUri,
                    if (isVideo) GeneratedMediaType.VIDEO else GeneratedMediaType.IMAGE,
                )
                val msg = res.fold(
                    onSuccess = { savedSuccessText },
                    onFailure = { context.getString(R.string.media_saved_error, it.message.orEmpty()) },
                )
                snackbarHostState.showSnackbar(msg)
            }
        }
    }

    fun triggerSave() {
        if (currentUri.isBlank()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            legacyPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            scope.launch {
                val res = mediaStoreSaver.saveToGallery(
                    currentUri,
                    if (isVideo) GeneratedMediaType.VIDEO else GeneratedMediaType.IMAGE,
                )
                val msg = res.fold(
                    onSuccess = { savedSuccessText },
                    onFailure = { context.getString(R.string.media_saved_error, it.message.orEmpty()) },
                )
                snackbarHostState.showSnackbar(msg)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.72f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            ) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    ),
                    title = {
                        Text(stringResource(R.string.media_viewer_title))
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("media_viewer_back")) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = ::triggerSave, modifier = Modifier.testTag("media_viewer_save")) {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = stringResource(R.string.media_save),
                            )
                        }
                        IconButton(
                            onClick = {
                                if (currentUri.isNotBlank()) {
                                    val intent = mediaStoreSaver.createShareIntent(
                                        localUriString = currentUri,
                                        mimeType = if (isVideo) "video/mp4" else "image/png",
                                        title = context.getString(R.string.action_share),
                                    )
                                    context.startActivity(intent)
                                }
                            },
                            modifier = Modifier.testTag("media_viewer_share"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Share,
                                contentDescription = stringResource(R.string.action_share),
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            if (isVideo) {
                if (currentUri.isNotBlank()) {
                    InlineVideoPlayer(mediaUri = currentUri)
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    ZoomableImagePage(imageUri = uris[page])
                }

                if (uris.size > 1) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 16.dp),
                    ) {
                        Text(
                            text = "${pagerState.currentPage + 1} / ${uris.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImagePage(imageUri: String) {
    var targetScale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "viewer_zoom_scale",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (targetScale > 1.1f) {
                            targetScale = 1f
                            offset = Offset.Zero
                        } else {
                            targetScale = 2.5f
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (targetScale * zoom).coerceIn(1f, 5f)
                    targetScale = nextScale
                    offset = if (nextScale > 1f) {
                        offset + pan
                    } else {
                        Offset.Zero
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = imageUri,
            contentDescription = stringResource(R.string.media_viewer_title),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = animatedScale,
                    scaleY = animatedScale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
    }
}
