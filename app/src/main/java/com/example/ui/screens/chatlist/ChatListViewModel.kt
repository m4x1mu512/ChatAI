package com.example.ui.screens.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.model.ChatSession
import com.example.core.model.ProviderConfig
import com.example.core.model.ProviderStatus
import com.example.data.repository.ChatRepository
import com.example.data.repository.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatListUiState(
    val chats: List<ChatSession> = emptyList(),
    val searchQuery: String = "",
    val showArchived: Boolean = false,
    val hasConnectedProvider: Boolean = false,
    val defaultProvider: ProviderConfig? = null,
)

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val showArchived = MutableStateFlow(false)

    val uiState: StateFlow<ChatListUiState> = combine(
        chatRepository.observeChats(),
        providerRepository.observeProviders(),
        searchQuery,
        showArchived,
    ) { chats, providers, query, archived ->
        val enabledProviders = providers.filter { it.isEnabled }
        val connected = enabledProviders.firstOrNull { it.status == ProviderStatus.CONNECTED }
            ?: enabledProviders.firstOrNull { it.hasApiKey || !it.type.requiresApiKey }
        val q = query.trim().lowercase()
        val filtered = chats
            .filter { it.isArchived == archived }
            .filter {
                q.isEmpty() ||
                    it.title.lowercase().contains(q) ||
                    it.providerName.lowercase().contains(q) ||
                    it.modelId.orEmpty().lowercase().contains(q)
            }
        ChatListUiState(
            chats = filtered,
            searchQuery = query,
            showArchived = archived,
            hasConnectedProvider = connected != null,
            defaultProvider = connected,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatListUiState())

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setShowArchived(archived: Boolean) {
        showArchived.value = archived
    }

    fun createNewChat(defaultTitle: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val provider = uiState.value.defaultProvider
            val created = chatRepository.createChat(
                title = defaultTitle,
                providerId = provider?.id,
                providerName = provider?.displayName.orEmpty(),
                modelId = provider?.selectedModelId,
            )
            onCreated(created.id)
        }
    }

    fun renameChat(chatId: String, newTitle: String) {
        viewModelScope.launch {
            if (newTitle.isNotBlank()) {
                chatRepository.renameChat(chatId, newTitle)
            }
        }
    }

    fun togglePin(chat: ChatSession) {
        viewModelScope.launch {
            chatRepository.setPinned(chat.id, !chat.isPinned)
        }
    }

    fun toggleArchive(chat: ChatSession) {
        viewModelScope.launch {
            chatRepository.setArchived(chat.id, !chat.isArchived)
        }
    }

    fun deleteChat(chatId: String) {
        viewModelScope.launch {
            chatRepository.deleteChat(chatId)
        }
    }
}
