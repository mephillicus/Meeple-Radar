package com.meepleradar.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meepleradar.data.local.AppThemeMode
import com.meepleradar.data.local.BggCollectionStore
import com.meepleradar.data.local.CacheMaintenance
import com.meepleradar.data.local.NotificationPreferences
import com.meepleradar.data.local.NotificationPreferencesStore
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.UserAlert
import com.meepleradar.data.repo.ListingRepository
import com.meepleradar.data.repo.LocalCacheStats
import com.meepleradar.data.repo.bestEurPrice
import com.meepleradar.notifications.SyncWorkScheduler
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsDiagnostics(
    val cacheStats: LocalCacheStats = LocalCacheStats(),
    val mediaCacheSizeBytes: Long = 0L,
    val bggUsername: String? = null,
    val bggGamesCount: Int = 0,
    val isResyncing: Boolean = false,
    val isClearingCache: Boolean = false,
    val isExportingFavorites: Boolean = false,
    val statusMessage: String? = null,
    val isStatusError: Boolean = false,
)

class AlertsViewModel(
    private val repository: ListingRepository,
    private val preferencesStore: NotificationPreferencesStore,
    private val syncWorkScheduler: SyncWorkScheduler,
    private val bggCollectionStore: BggCollectionStore? = null,
    private val cacheMaintenance: CacheMaintenance? = null,
) : ViewModel() {

    private val _alerts = MutableStateFlow<List<UserAlert>>(emptyList())
    val alerts: StateFlow<List<UserAlert>> = _alerts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _diagnostics = MutableStateFlow(SettingsDiagnostics())
    val diagnostics: StateFlow<SettingsDiagnostics> = _diagnostics.asStateFlow()

    val preferences: StateFlow<NotificationPreferences> = preferencesStore.preferencesFlow

    val unreadCount: StateFlow<Int> = _alerts
        .map { list -> list.count { it.isUnread } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        viewModelScope.launch {
            repository.catalogRevisionFlow()
                .drop(1)
                .collect {
                    refresh()
                    refreshDiagnostics()
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _alerts.value = repository.userAlerts()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silenzioso: mantiene gli avvisi già in memoria se offline.
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshDiagnostics() {
        viewModelScope.launch {
            try {
                val stats = repository.localCacheStats()
                val mediaBytes = cacheMaintenance?.cacheSizeBytes() ?: 0L
                val bggCollection = withContext(Dispatchers.IO) {
                    runCatching { bggCollectionStore?.load() }.getOrNull()
                }
                _diagnostics.value = _diagnostics.value.copy(
                    cacheStats = stats,
                    mediaCacheSizeBytes = mediaBytes,
                    bggUsername = bggCollection?.username,
                    bggGamesCount = bggCollection?.games?.size ?: 0,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Mantiene i dati precedenti se non disponibili.
            }
        }
    }

    fun forceFullResync() {
        if (_diagnostics.value.isResyncing) return
        viewModelScope.launch {
            _diagnostics.value = _diagnostics.value.copy(
                isResyncing = true,
                statusMessage = null,
                isStatusError = false,
            )
            try {
                val updatedStats = repository.forceFullResync(LocalDate.now())
                _alerts.value = runCatching { repository.userAlerts() }.getOrDefault(_alerts.value)
                _diagnostics.value = _diagnostics.value.copy(
                    cacheStats = updatedStats,
                    isResyncing = false,
                    statusMessage = "Catalogo risincronizzato (${updatedStats.listingsCount} offerte, ${updatedStats.gamesCount} giochi)",
                    isStatusError = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _diagnostics.value = _diagnostics.value.copy(
                    isResyncing = false,
                    statusMessage = e.message ?: "Errore durante la risincronizzazione completa.",
                    isStatusError = true,
                )
            }
        }
    }

    fun clearMediaCache() {
        if (_diagnostics.value.isClearingCache) return
        viewModelScope.launch {
            _diagnostics.value = _diagnostics.value.copy(
                isClearingCache = true,
                statusMessage = null,
                isStatusError = false,
            )
            try {
                val remainingBytes = cacheMaintenance?.clearImageAndHttpCache() ?: 0L
                _diagnostics.value = _diagnostics.value.copy(
                    mediaCacheSizeBytes = remainingBytes,
                    isClearingCache = false,
                    statusMessage = "Cache immagini e HTTP svuotata.",
                    isStatusError = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _diagnostics.value = _diagnostics.value.copy(
                    isClearingCache = false,
                    statusMessage = e.message ?: "Impossibile svuotare la cache.",
                    isStatusError = true,
                )
            }
        }
    }

    fun exportFavorites(onShareTextReady: (String) -> Unit) {
        if (_diagnostics.value.isExportingFavorites) return
        viewModelScope.launch {
            _diagnostics.value = _diagnostics.value.copy(
                isExportingFavorites = true,
                statusMessage = null,
                isStatusError = false,
            )
            try {
                val favorites = repository.favorites(LocalDate.now())
                if (favorites.isEmpty()) {
                    _diagnostics.value = _diagnostics.value.copy(
                        isExportingFavorites = false,
                        statusMessage = "Nessun gioco nei preferiti da condividere.",
                        isStatusError = false,
                    )
                } else {
                    val shareText = formatFavoritesShareText(favorites)
                    _diagnostics.value = _diagnostics.value.copy(
                        isExportingFavorites = false,
                        statusMessage = null,
                        isStatusError = false,
                    )
                    onShareTextReady(shareText)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _diagnostics.value = _diagnostics.value.copy(
                    isExportingFavorites = false,
                    statusMessage = e.message ?: "Impossibile esportare i preferiti.",
                    isStatusError = true,
                )
            }
        }
    }

    fun markRead(alert: UserAlert) {
        if (!alert.isUnread) return
        val nowIso = Instant.now().toString()
        _alerts.value = _alerts.value.map { current ->
            if (current.id == alert.id) current.copy(readAt = nowIso) else current
        }
        viewModelScope.launch {
            runCatching { repository.markAlertRead(alert.id) }
        }
    }

    fun markAllRead() {
        val unreadIds = _alerts.value.filter { it.isUnread }.map { it.id }
        if (unreadIds.isEmpty()) return
        val nowIso = Instant.now().toString()
        val idSet = unreadIds.toSet()
        _alerts.value = _alerts.value.map { current ->
            if (current.id in idSet) current.copy(readAt = nowIso) else current
        }
        viewModelScope.launch {
            runCatching { repository.markAllAlertsRead(unreadIds) }
        }
    }

    fun setBackgroundSyncEnabled(enabled: Boolean) {
        val next = preferencesStore.update { it.copy(backgroundSyncEnabled = enabled) }
        syncWorkScheduler.syncSchedule(
            enabled = next.backgroundSyncEnabled,
            intervalHours = next.syncIntervalHours.toLong(),
            wifiOnly = next.syncWifiOnly,
        )
    }

    fun setSyncIntervalHours(hours: Int) {
        val next = preferencesStore.update { it.copy(syncIntervalHours = hours) }
        if (next.backgroundSyncEnabled) {
            syncWorkScheduler.syncSchedule(
                enabled = true,
                intervalHours = next.syncIntervalHours.toLong(),
                wifiOnly = next.syncWifiOnly,
                replaceExisting = true,
            )
        }
    }

    fun setSyncWifiOnly(wifiOnly: Boolean) {
        val next = preferencesStore.update { it.copy(syncWifiOnly = wifiOnly) }
        if (next.backgroundSyncEnabled) {
            syncWorkScheduler.syncSchedule(
                enabled = true,
                intervalHours = next.syncIntervalHours.toLong(),
                wifiOnly = next.syncWifiOnly,
                replaceExisting = true,
            )
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        preferencesStore.update { it.copy(themeMode = mode) }
    }

    fun setDynamicColors(enabled: Boolean) {
        preferencesStore.update { it.copy(dynamicColors = enabled) }
    }

    fun setNotifyBackInStock(enabled: Boolean) {
        preferencesStore.update { it.copy(notifyBackInStock = enabled) }
    }

    fun setNotifyPriceDrop(enabled: Boolean) {
        preferencesStore.update { it.copy(notifyPriceDrop = enabled) }
    }

    fun setMinPriceDropPct(minPct: Int) {
        preferencesStore.update { it.copy(minPriceDropPct = minPct) }
    }

    fun setQuietHoursEnabled(enabled: Boolean) {
        preferencesStore.update { it.copy(quietHoursEnabled = enabled) }
    }

    fun setNotifyNewExpansion(enabled: Boolean) {
        preferencesStore.update { it.copy(notifyNewExpansion = enabled) }
    }

    fun setNotifyNewsDigest(enabled: Boolean) {
        preferencesStore.update { it.copy(notifyNewsDigest = enabled) }
    }
}

fun formatFavoritesShareText(items: List<IncomingItem>): String = buildString {
    appendLine("I miei giochi preferiti su Meeple Radar (${items.size}):")
    appendLine()
    items.sortedBy { it.title.lowercase(Locale.ROOT) }.forEach { item ->
        val bestPrice = bestEurPrice(item.offers)
        val bestOffer = if (bestPrice != null) {
            item.offers.firstOrNull {
                it.price?.currency == "EUR" && it.price.amount == bestPrice.amount
            } ?: item.offers.firstOrNull()
        } else {
            item.offers.firstOrNull()
        }

        append("• ")
        append(item.title)
        if (bestPrice != null) {
            append(String.format(Locale.ITALY, " — €%.2f", bestPrice.amount))
            val store = bestOffer?.sourceName?.takeIf(String::isNotBlank)
            if (store != null) append(" ($store)")
        } else {
            append(" — prezzo n/d")
        }
        appendLine()
        bestOffer?.url?.takeIf(String::isNotBlank)?.let { url ->
            append("  ")
            appendLine(url)
        }
    }
}.trimEnd()
