package com.example.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.R
import com.example.core.model.ProviderType
import com.example.ui.screens.chat.ChatScreen
import com.example.ui.screens.chat.ChatViewModel
import com.example.ui.screens.chatlist.ChatListScreen
import com.example.ui.screens.chatlist.ChatListViewModel
import com.example.ui.screens.github.GitHubScreen
import com.example.ui.screens.github.GitHubViewModel
import com.example.ui.screens.media.MediaViewerScreen
import com.example.ui.screens.providers.ProviderConfigScreen
import com.example.ui.screens.providers.ProvidersScreen
import com.example.ui.screens.providers.ProvidersViewModel
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.settings.SettingsViewModel
import kotlinx.serialization.Serializable

@Serializable
data object ProvidersRoute

@Serializable
data class ProviderEditRoute(
    val providerId: String? = null,
    val presetType: String? = null,
)

@Serializable
data object ChatsRoute

@Serializable
data class ChatDetailRoute(val chatId: String)

@Serializable
data class MediaViewerRoute(
    val uris: List<String>,
    val initialIndex: Int = 0,
    val isVideo: Boolean = false,
)

@Serializable
data object SettingsRoute

@Serializable
data object GitHubRoute

private enum class TopTab {
    CHATS,
    PROVIDERS,
    SETTINGS,
}

@Composable
fun AiChatAppRoot(
    windowSizeClass: WindowSizeClass,
    onboardingCompleted: Boolean,
    hasConfiguredProviders: Boolean,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination

    val isExpanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded
    val showTopLevelNav = currentDest?.hasRoute<ChatsRoute>() == true ||
        currentDest?.hasRoute<ProvidersRoute>() == true ||
        currentDest?.hasRoute<SettingsRoute>() == true

    val activeTab = when {
        currentDest?.hasRoute<ProvidersRoute>() == true -> TopTab.PROVIDERS
        currentDest?.hasRoute<SettingsRoute>() == true -> TopTab.SETTINGS
        else -> TopTab.CHATS
    }

    // Start at Providers onboarding if first launch and no providers configured yet
    val startDestination: Any = remember {
        if (!onboardingCompleted && !hasConfiguredProviders) ProvidersRoute else ChatsRoute
    }

    var tabletSelectedChatId by rememberSaveable { mutableStateOf<String?>(null) }

    fun navigateTopTab(tab: TopTab) {
        val route: Any = when (tab) {
            TopTab.CHATS -> ChatsRoute
            TopTab.PROVIDERS -> ProvidersRoute
            TopTab.SETTINGS -> SettingsRoute
        }
        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(
                visible = showTopLevelNav && !isExpanded,
                enter = slideInVertically(tween(180)) { it } + fadeIn(tween(180)),
                exit = slideOutVertically(tween(150)) { it } + fadeOut(tween(150)),
            ) {
                Column {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.background,
                        tonalElevation = 0.dp,
                    ) {
                        NavigationBarItem(
                            selected = activeTab == TopTab.CHATS,
                            onClick = { navigateTopTab(TopTab.CHATS) },
                            icon = {
                                Icon(
                                    if (activeTab == TopTab.CHATS) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(R.string.nav_chats)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            modifier = Modifier.testTag("nav_chats"),
                        )
                        NavigationBarItem(
                            selected = activeTab == TopTab.PROVIDERS,
                            onClick = { navigateTopTab(TopTab.PROVIDERS) },
                            icon = {
                                Icon(
                                    if (activeTab == TopTab.PROVIDERS) Icons.Filled.Hub else Icons.Outlined.Hub,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(R.string.nav_providers)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            modifier = Modifier.testTag("nav_providers"),
                        )
                        NavigationBarItem(
                            selected = activeTab == TopTab.SETTINGS,
                            onClick = { navigateTopTab(TopTab.SETTINGS) },
                            icon = {
                                Icon(
                                    if (activeTab == TopTab.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(R.string.nav_settings)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            modifier = Modifier.testTag("nav_settings"),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (showTopLevelNav && isExpanded) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxHeight().width(88.dp),
                ) {
                    NavigationRailItem(
                        selected = activeTab == TopTab.CHATS,
                        onClick = { navigateTopTab(TopTab.CHATS) },
                        icon = {
                            Icon(
                                if (activeTab == TopTab.CHATS) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(R.string.nav_chats)) },
                    )
                    NavigationRailItem(
                        selected = activeTab == TopTab.PROVIDERS,
                        onClick = { navigateTopTab(TopTab.PROVIDERS) },
                        icon = {
                            Icon(
                                if (activeTab == TopTab.PROVIDERS) Icons.Filled.Hub else Icons.Outlined.Hub,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(R.string.nav_providers)) },
                    )
                    NavigationRailItem(
                        selected = activeTab == TopTab.SETTINGS,
                        onClick = { navigateTopTab(TopTab.SETTINGS) },
                        icon = {
                            Icon(
                                if (activeTab == TopTab.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(R.string.nav_settings)) },
                    )
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            NavHost(
                navController = navController,
                startDestination = startDestination,
                enterTransition = { fadeIn(tween(180)) },
                exitTransition = { fadeOut(tween(150)) },
                popEnterTransition = { fadeIn(tween(180)) },
                popExitTransition = { fadeOut(tween(150)) },
                modifier = Modifier.weight(1f),
            ) {
                composable<ProvidersRoute> {
                    val vm: ProvidersViewModel = hiltViewModel()
                    ProvidersScreen(
                        viewModel = vm,
                        isFirstLaunchOnboarding = !onboardingCompleted,
                        onAddProvider = { type ->
                            navController.navigate(ProviderEditRoute(presetType = type.name))
                        },
                        onEditProvider = { id ->
                            navController.navigate(ProviderEditRoute(providerId = id))
                        },
                        onContinueToChats = { navigateTopTab(TopTab.CHATS) },
                    )
                }

                composable<ProviderEditRoute>(
                    enterTransition = {
                        slideInHorizontally(tween(220)) { it / 6 } + fadeIn(tween(220))
                    },
                    exitTransition = { fadeOut(tween(150)) },
                    popEnterTransition = { fadeIn(tween(180)) },
                    popExitTransition = {
                        slideOutHorizontally(tween(200)) { it / 6 } + fadeOut(tween(180))
                    },
                ) { entry ->
                    val route = entry.toRoute<ProviderEditRoute>()
                    val vm: ProvidersViewModel = hiltViewModel()
                    LaunchedEffect(route.providerId, route.presetType) {
                        if (route.providerId != null) {
                            vm.loadExistingProvider(route.providerId)
                        } else {
                            val type = route.presetType?.let {
                                runCatching { ProviderType.valueOf(it) }.getOrNull()
                            } ?: ProviderType.OPENAI
                            vm.startNewProvider(type)
                        }
                    }
                    ProviderConfigScreen(
                        viewModel = vm,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable<ChatsRoute> {
                    val listVm: ChatListViewModel = hiltViewModel()
                    if (isExpanded) {
                        val listState by listVm.uiState.collectAsStateWithLifecycle()
                        LaunchedEffect(listState.chats) {
                            if (tabletSelectedChatId == null && listState.chats.isNotEmpty()) {
                                tabletSelectedChatId = listState.chats.first().id
                            }
                        }
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.width(360.dp).fillMaxHeight()) {
                                ChatListScreen(
                                    viewModel = listVm,
                                    selectedChatId = tabletSelectedChatId,
                                    onOpenChat = { id -> tabletSelectedChatId = id },
                                    onOpenProviders = { navigateTopTab(TopTab.PROVIDERS) },
                                )
                            }
                            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                val selectedId = tabletSelectedChatId
                                if (selectedId != null) {
                                    val chatVm: ChatViewModel = hiltViewModel()
                                    LaunchedEffect(selectedId) {
                                        chatVm.loadChat(selectedId)
                                    }
                                    ChatScreen(
                                        viewModel = chatVm,
                                        showBackButton = false,
                                        onBack = {},
                                        onOpenImageViewer = { uris, idx ->
                                            navController.navigate(MediaViewerRoute(uris = uris, initialIndex = idx, isVideo = false))
                                        },
                                        onOpenVideoViewer = { videoUri ->
                                            navController.navigate(MediaViewerRoute(uris = listOf(videoUri), initialIndex = 0, isVideo = true))
                                        },
                                        onOpenProviders = { navigateTopTab(TopTab.PROVIDERS) },
                                        onOpenGitHub = { navController.navigate(GitHubRoute) },
                                        onOpenChat = { id -> navController.navigate(ChatDetailRoute(id)) },
                                    )
                                } else {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = stringResource(R.string.chats_empty_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        ChatListScreen(
                            viewModel = listVm,
                            selectedChatId = null,
                            onOpenChat = { id -> navController.navigate(ChatDetailRoute(id)) },
                            onOpenProviders = { navigateTopTab(TopTab.PROVIDERS) },
                        )
                    }
                }

                composable<ChatDetailRoute>(
                    enterTransition = {
                        slideInHorizontally(tween(220)) { it / 6 } + fadeIn(tween(220))
                    },
                    exitTransition = { fadeOut(tween(150)) },
                    popEnterTransition = { fadeIn(tween(180)) },
                    popExitTransition = {
                        slideOutHorizontally(tween(200)) { it / 6 } + fadeOut(tween(180))
                    },
                ) { entry ->
                    val route = entry.toRoute<ChatDetailRoute>()
                    val chatVm: ChatViewModel = hiltViewModel()
                    LaunchedEffect(route.chatId) {
                        chatVm.loadChat(route.chatId)
                    }
                    ChatScreen(
                        viewModel = chatVm,
                        showBackButton = true,
                        onBack = { navController.popBackStack() },
                        onOpenImageViewer = { uris, idx ->
                            navController.navigate(MediaViewerRoute(uris = uris, initialIndex = idx, isVideo = false))
                        },
                        onOpenVideoViewer = { videoUri ->
                            navController.navigate(MediaViewerRoute(uris = listOf(videoUri), initialIndex = 0, isVideo = true))
                        },
                        onOpenProviders = { navigateTopTab(TopTab.PROVIDERS) },
                        onOpenGitHub = { navController.navigate(GitHubRoute) },
                        onOpenChat = { id -> navController.navigate(ChatDetailRoute(id)) },
                    )
                }

                composable<MediaViewerRoute>(
                    enterTransition = { fadeIn(tween(200)) },
                    exitTransition = { fadeOut(tween(150)) },
                    popEnterTransition = { fadeIn(tween(180)) },
                    popExitTransition = { fadeOut(tween(180)) },
                ) { entry ->
                    val route = entry.toRoute<MediaViewerRoute>()
                    MediaViewerScreen(
                        uris = route.uris,
                        initialIndex = route.initialIndex,
                        isVideo = route.isVideo,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable<SettingsRoute> {
                    val vm: SettingsViewModel = hiltViewModel()
                    SettingsScreen(
                        viewModel = vm,
                        onOpenGitHub = { navController.navigate(GitHubRoute) },
                    )
                }

                composable<GitHubRoute>(
                    enterTransition = {
                        slideInHorizontally(tween(220)) { it / 6 } + fadeIn(tween(220))
                    },
                    exitTransition = { fadeOut(tween(150)) },
                    popEnterTransition = { fadeIn(tween(180)) },
                    popExitTransition = {
                        slideOutHorizontally(tween(200)) { it / 6 } + fadeOut(tween(180))
                    },
                ) {
                    val vm: GitHubViewModel = hiltViewModel()
                    GitHubScreen(
                        viewModel = vm,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}
