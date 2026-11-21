package com.meepleradar.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meepleradar.data.local.NotificationPreferences
import com.meepleradar.data.local.NotificationPreferencesStore
import com.meepleradar.data.model.UserAlert
import com.meepleradar.data.repo.ListingRepository
import com.meepleradar.notifications.SyncWorkScheduler
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlertsViewModel(
    private val repository: ListingRepository,
    private val preferencesStore: NotificationPreferencesStore,
    private val syncWorkScheduler: SyncWorkScheduler,
) : ViewModel() {

    private val _alerts = MutableStateFlow<List<UserAlert>>(emptyList())
    val alerts: StateFlow<List<UserAlert>> = _alerts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val preferences: StateFlow<NotificationPreferences> = preferencesStore.preferencesFlow

    val unreadCount: StateFlow<Int> = _alerts
        .map { list -> list.count { it.isUnread } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

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
        preferencesStore.update { it.copy(backgroundSyncEnabled = enabled) }
        syncWorkScheduler.syncSchedule(enabled)
    }

    fun setNotifyBackInStock(enabled: Boolean) {
        preferencesStore.update { it.copy(notifyBackInStock = enabled) }
    }

    fun setNotifyPriceDrop(enabled: Boolean) {
        preferencesStore.update { it.copy(notifyPriceDrop = enabled) }
    }

    fun setNotifyNewExpansion(enabled: Boolean) {
        preferencesStore.update { it.copy(notifyNewExpansion = enabled) }
    }

    fun setNotifyNewsDigest(enabled: Boolean) {
        preferencesStore.update { it.copy(notifyNewsDigest = enabled) }
    }
}
