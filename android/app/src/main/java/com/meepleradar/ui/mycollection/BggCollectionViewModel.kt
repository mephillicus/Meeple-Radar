package com.meepleradar.ui.mycollection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meepleradar.data.local.BggCollectionStore
import com.meepleradar.data.local.CachedBggCollection
import com.meepleradar.data.model.Action
import com.meepleradar.data.model.BggCollectionGame
import com.meepleradar.data.model.BggExpansion
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.remote.BggCollectionApi
import com.meepleradar.data.remote.BggLink
import com.meepleradar.data.repo.ListingRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BggCollectionUiState {
    data class Setup(
        val username: String = "",
        val apiToken: String = "",
        val error: String? = null,
    ) : BggCollectionUiState

    data class Loading(val username: String) : BggCollectionUiState

    data class Content(
        val username: String,
        val games: List<BggCollectionGame>,
        val updatedAt: Long,
        val refreshing: Boolean = false,
        val syncMessage: String? = null,
        val error: String? = null,
        val listingsByBggId: Map<Int, IncomingItem> = emptyMap(),
        val favoriteKeys: Set<String> = emptySet(),
        val favoriteActionKeysByBggId: Map<Int, Set<String>> = emptyMap(),
        val expansionsByGame: Map<Int, ExpansionLoadState> = emptyMap(),
        val expandedSections: Map<Int, Set<ExpansionOwnership>> = emptyMap(),
        val expansionAvailabilityById: Map<Int, ExpansionAvailability> = emptyMap(),
    ) : BggCollectionUiState
}

sealed interface ExpansionLoadState {
    data object Loading : ExpansionLoadState
    data class Loaded(val items: List<BggLink>) : ExpansionLoadState
    data class Error(val message: String) : ExpansionLoadState
}

enum class ExpansionAvailabilityType {
    NEWS,
    PREORDER,
}

data class ExpansionAvailability(
    val item: IncomingItem,
    val type: ExpansionAvailabilityType,
)

enum class ExpansionOwnership {
    OWNED,
    NOT_OWNED,
}

class BggCollectionViewModel(
    private val api: BggCollectionApi,
    private val store: BggCollectionStore,
    private val repository: ListingRepository,
) : ViewModel() {

    private val cached = store.load()
    private val _state = MutableStateFlow<BggCollectionUiState>(
        cached?.let {
            BggCollectionUiState.Content(
                it.username,
                it.games,
                it.updatedAt,
                favoriteKeys = emptySet(),
                expansionsByGame = if (it.expansionsLoaded) {
                    expansionStates(it.expansions, it.games.filterNot { game -> game.isExpansion() }.map { game -> game.bggId })
                } else {
                    emptyMap()
                },
            )
        } ?: BggCollectionUiState.Setup(),
    )
    val state: StateFlow<BggCollectionUiState> = _state.asStateFlow()
    private var cachedApiToken: String? = cached?.apiToken
    private var cachedExpansions: List<BggExpansion> = cached?.expansions.orEmpty()
    private var expansionsLoaded = cached?.expansionsLoaded == true

    init {
        cached?.let { loadListingData() }
    }

    fun connect(username: String, apiToken: String) {
        val cleanUsername = username.trim()
        if (cleanUsername.isBlank()) {
            _state.value = BggCollectionUiState.Setup(username = cleanUsername, error = "Inserisci il nome utente BGG.")
            return
        }
        if (apiToken.isBlank()) {
            _state.value = BggCollectionUiState.Setup(
                username = cleanUsername,
                error = "Inserisci il token dell'applicazione BGG.",
            )
            return
        }
        fetch(cleanUsername, apiToken.trim(), previousContent = null)
    }

    fun refresh() {
        val current = _state.value as? BggCollectionUiState.Content ?: return
        val apiToken = cachedApiToken.orEmpty()
        if (apiToken.isBlank()) {
            _state.value = BggCollectionUiState.Setup(
                username = current.username,
                error = "Per aggiornare la collezione serve il token dell'applicazione BGG.",
            )
            return
        }
        fetch(current.username, apiToken, current)
    }

    fun logout() {
        store.clear()
        cachedApiToken = null
        cachedExpansions = emptyList()
        expansionsLoaded = false
        _state.value = BggCollectionUiState.Setup()
    }

    fun toggleExpansionSection(gameId: Int, ownership: ExpansionOwnership) {
        val current = _state.value as? BggCollectionUiState.Content ?: return
        val open = current.expandedSections[gameId].orEmpty()
        val next = if (ownership in open) open - ownership else open + ownership
        _state.value = current.copy(expandedSections = current.expandedSections + (gameId to next))
        if (next.isNotEmpty() && current.expansionsByGame[gameId] == null) loadExpansions(gameId)
    }

    fun retryExpansions(gameId: Int) {
        val current = _state.value as? BggCollectionUiState.Content ?: return
        if (current.expansionsByGame[gameId] !is ExpansionLoadState.Loading) loadExpansions(gameId)
    }

    fun toggleFavorite(bggId: Int) {
        val current = _state.value as? BggCollectionUiState.Content ?: return
        val keys = current.favoriteActionKeysByBggId[bggId].orEmpty()
        if (keys.isEmpty()) return
        val wasFavorite = keys.any { it in current.favoriteKeys }
        val action = if (wasFavorite) Action.UNFAVORITED else Action.FAVORITE
        viewModelScope.launch {
            try {
                keys.forEach { key -> repository.setAction(key, action) }
                val latest = _state.value as? BggCollectionUiState.Content ?: return@launch
                _state.value = latest.copy(
                    favoriteKeys = if (wasFavorite) latest.favoriteKeys - keys else latest.favoriteKeys + keys,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val latest = _state.value as? BggCollectionUiState.Content ?: return@launch
                _state.value = latest.copy(error = e.message ?: "Impossibile aggiornare il preferito.")
            }
        }
    }

    fun ignore(key: String) {
        if (_state.value !is BggCollectionUiState.Content) return
        viewModelScope.launch {
            try {
                repository.setAction(key, Action.IGNORED)
                val latest = _state.value as? BggCollectionUiState.Content ?: return@launch
                _state.value = latest.copy(listingsByBggId = latest.listingsByBggId.filterValues { it.key != key })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val latest = _state.value as? BggCollectionUiState.Content ?: return@launch
                _state.value = latest.copy(error = e.message ?: "Impossibile ignorare il gioco.")
            }
        }
    }

    private fun loadListingData() {
        viewModelScope.launch {
            try {
                val today = LocalDate.now()
                val items = repository.incoming(
                    today = today,
                    includeUndated = true,
                    includePriceless = true,
                )
                val preorderItems = repository.preorderItems(today)
                val newsItems = repository.newsItems(today)
                val favorites = repository.favorites(
                    today = today,
                    includeUndated = true,
                    includePriceless = true,
                ).map { it.key }.toSet()
                val latest = _state.value as? BggCollectionUiState.Content ?: return@launch
                val availabilityById = mutableMapOf<Int, ExpansionAvailability>()
                newsItems.forEach { item ->
                    item.game?.bggId?.let { bggId ->
                        availabilityById[bggId] = ExpansionAvailability(item, ExpansionAvailabilityType.NEWS)
                    }
                }
                preorderItems.forEach { item ->
                    item.game?.bggId?.let { bggId ->
                        availabilityById[bggId] = ExpansionAvailability(item, ExpansionAvailabilityType.PREORDER)
                    }
                }
                _state.value = latest.copy(
                    listingsByBggId = items.groupByNotNull { it.game?.bggId }
                        .mapValues { (_, group) ->
                            val representative = group.maxBy { it.offers.size }
                            representative.copy(
                                offers = group.flatMap { it.offers }.distinctBy { it.sourceId to it.url },
                                bggSelectionKeys = group.flatMap { it.bggSelectionKeys }.distinct(),
                            )
                        },
                    favoriteKeys = favorites,
                    favoriteActionKeysByBggId = items.groupByNotNull { it.game?.bggId }
                        .mapValues { (_, group) -> group.map { it.key }.toSet() },
                    expansionAvailabilityById = availabilityById,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val latest = _state.value as? BggCollectionUiState.Content ?: return@launch
                _state.value = latest.copy(error = e.message ?: "Impossibile caricare prezzi e offerte locali.")
            }
        }
    }

    private inline fun <T, K : Any> Iterable<T>.groupByNotNull(keySelector: (T) -> K?): Map<K, List<T>> =
        mapNotNull { item -> keySelector(item)?.let { it to item } }.groupBy({ it.first }, { it.second })

    private fun loadExpansions(gameId: Int) {
        val current = _state.value as? BggCollectionUiState.Content ?: return
        if (!expansionsLoaded) {
            _state.value = current.copy(
                expansionsByGame = current.expansionsByGame + (gameId to ExpansionLoadState.Loading),
            )
            refresh()
            return
        }
        val links = cachedExpansions.filter { it.expandsBggId == gameId }
            .map { BggLink(it.bggId, it.name, it.url) }
        _state.value = current.copy(
            expansionsByGame = current.expansionsByGame + (gameId to ExpansionLoadState.Loaded(links)),
        )
    }

    private fun expansionStates(
        expansions: List<BggExpansion>,
        baseGameIds: List<Int>,
    ): Map<Int, ExpansionLoadState> =
        baseGameIds.distinct().associateWith { baseId ->
            ExpansionLoadState.Loaded(
                expansions.filter { it.expandsBggId == baseId }
                    .map { BggLink(it.bggId, it.name, it.url) },
            )
        }

    private fun fetch(username: String, apiToken: String, previousContent: BggCollectionUiState.Content?) {
        _state.value = previousContent?.copy(refreshing = true, syncMessage = "Connessione a BGG...", error = null)
            ?: BggCollectionUiState.Loading(username)
        viewModelScope.launch {
            try {
                val current1 = _state.value as? BggCollectionUiState.Content
                if (current1 != null) _state.value = current1.copy(syncMessage = "Download collezione...")

                val ownedGames = api.ownedGames(username, apiToken)
                val gameIds = ownedGames.map { it.bggId }

                val current2 = _state.value as? BggCollectionUiState.Content
                if (current2 != null) _state.value = current2.copy(syncMessage = "Download espansioni...")

                var expansionsError: String? = null
                val expansions = try {
                    api.expansions(gameIds, apiToken)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    expansionsError = e.message ?: "Impossibile scaricare l'elenco delle espansioni."
                    emptyList()
                }

                val current3 = _state.value as? BggCollectionUiState.Content
                if (current3 != null) _state.value = current3.copy(syncMessage = "Sincronizzazione offerte...")

                val expansionsAreLoaded = expansionsError == null
                val expansionById = expansions.associateBy { it.bggId }
                val games = ownedGames.map { game ->
                    expansionById[game.bggId]?.let { expansion ->
                        game.copy(kind = "boardgameexpansion", expandsBggId = expansion.expandsBggId)
                    } ?: game
                }
                val expansionStates = if (expansionsAreLoaded) {
                    expansionStates(expansions, games.filterNot { it.isExpansion() }.map { it.bggId })
                } else {
                    games.filterNot { it.isExpansion() }.associate { game ->
                        game.bggId to ExpansionLoadState.Error(
                            requireNotNull(expansionsError),
                        )
                    }
                }
                val updatedAt = LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                cachedExpansions = expansions
                expansionsLoaded = expansionsAreLoaded
                store.save(
                    CachedBggCollection(
                        username = username,
                        games = games,
                        updatedAt = updatedAt,
                        apiToken = apiToken,
                        expansions = expansions,
                        expansionsLoaded = expansionsAreLoaded,
                    ),
                )
                cachedApiToken = apiToken
                _state.value = BggCollectionUiState.Content(
                    username = username,
                    games = games,
                    updatedAt = updatedAt,
                    error = expansionsError,
                    syncMessage = null,
                    expansionsByGame = expansionStates,
                )
                loadListingData()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "Impossibile caricare la collezione BGG."
                _state.value = previousContent?.copy(refreshing = false, syncMessage = null, error = message)
                    ?: BggCollectionUiState.Setup(username = username, apiToken = apiToken, error = message)
            }
        }
    }

    private fun BggCollectionGame.isExpansion(): Boolean =
        kind.equals("expansion", ignoreCase = true) ||
            kind.equals("boardgameexpansion", ignoreCase = true) ||
            expandsBggId != null
}
