package com.meepleradar.ui.components

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meepleradar.data.model.AvailabilityStatus
import com.meepleradar.data.model.Source
import com.meepleradar.notifications.NotificationHelper
import com.meepleradar.ui.alerts.AlertsPanelSection
import com.meepleradar.ui.alerts.AlertsViewModel
import com.meepleradar.ui.alerts.SettingsPanelSection
import com.meepleradar.ui.alerts.SheetTabButton
import com.meepleradar.ui.alerts.openAppNotificationSettings
import com.meepleradar.ui.alerts.sendTestNotification

@Composable
fun ListingFilterDrawerOverlay(
    sources: List<Source>,
    selectedSources: Set<String>?,
    hideUndated: Boolean,
    hidePriceless: Boolean,
    hideSeen: Boolean,
    onlyDiscounted: Boolean = false,
    selectedAvailability: Set<AvailabilityStatus>,
    sortOption: ListingSortOption,
    onApply: (
        sources: Set<String>?,
        hideUndated: Boolean,
        hidePriceless: Boolean,
        hideSeen: Boolean,
        onlyDiscounted: Boolean,
        availability: Set<AvailabilityStatus>,
        sortOption: ListingSortOption,
    ) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    onSortOptionChange: ((ListingSortOption) -> Unit)? = null,
    ignoredCount: Int = 0,
    onManageIgnored: (() -> Unit)? = null,
    unreadAlertCount: Int = 0,
    onOpenAlerts: (() -> Unit)? = null,
    alertsVm: AlertsViewModel? = null,
    onOpenAlertItem: ((String) -> Unit)? = null,
) {
    Popup(
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = true, clippingEnabled = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(onClick = onClose),
        ) {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(340.dp)
                    .clickable(enabled = false) {},
            ) {
                ListingFilterDrawerContent(
                    sources = sources,
                    selectedSources = selectedSources,
                    hideUndated = hideUndated,
                    hidePriceless = hidePriceless,
                    hideSeen = hideSeen,
                    onlyDiscounted = onlyDiscounted,
                    selectedAvailability = selectedAvailability,
                    sortOption = sortOption,
                    onApply = onApply,
                    onReset = onReset,
                    onClose = onClose,
                    onSortOptionChange = onSortOptionChange,
                    ignoredCount = ignoredCount,
                    onManageIgnored = onManageIgnored,
                    unreadAlertCount = unreadAlertCount,
                    onOpenAlerts = onOpenAlerts,
                    alertsVm = alertsVm,
                    onOpenAlertItem = onOpenAlertItem,
                )
            }
        }
    }
}

@Composable
fun ListingFilterDrawerContent(
    sources: List<Source>,
    selectedSources: Set<String>?,
    hideUndated: Boolean,
    hidePriceless: Boolean,
    hideSeen: Boolean,
    onlyDiscounted: Boolean = false,
    selectedAvailability: Set<AvailabilityStatus>,
    sortOption: ListingSortOption,
    onApply: (
        sources: Set<String>?,
        hideUndated: Boolean,
        hidePriceless: Boolean,
        hideSeen: Boolean,
        onlyDiscounted: Boolean,
        availability: Set<AvailabilityStatus>,
        sortOption: ListingSortOption,
    ) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    onSortOptionChange: ((ListingSortOption) -> Unit)? = null,
    ignoredCount: Int = 0,
    onManageIgnored: (() -> Unit)? = null,
    unreadAlertCount: Int = 0,
    onOpenAlerts: (() -> Unit)? = null,
    alertsVm: AlertsViewModel? = null,
    onOpenAlertItem: ((String) -> Unit)? = null,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    var localHideUndated by remember(hideUndated) { mutableStateOf(hideUndated) }
    var localHidePriceless by remember(hidePriceless) { mutableStateOf(hidePriceless) }
    var localHideSeen by remember(hideSeen) { mutableStateOf(hideSeen) }
    var localOnlyDiscounted by remember(onlyDiscounted) { mutableStateOf(onlyDiscounted) }
    var localSortOption by remember(sortOption) { mutableStateOf(sortOption) }
    var localSelectedSources by remember(selectedSources, sources) {
        mutableStateOf(
            selectedSources?.toMutableSet() ?: sources.map { it.id }.toMutableSet(),
        )
    }
    var localAvailability by remember(selectedAvailability) {
        mutableStateOf(selectedAvailability.toMutableSet())
    }

    val sourcesForApply = {
        if (sources.isEmpty() || localSelectedSources.size == sources.size) {
            null
        } else {
            localSelectedSources.toSet()
        }
    }

    val activeFilterCount = remember(
        localHideUndated,
        localHidePriceless,
        localHideSeen,
        localOnlyDiscounted,
        localSortOption,
        localSelectedSources,
        localAvailability,
        sources.size,
    ) {
        var count = 0
        if (localSortOption != ListingSortOption.DEFAULT) count++
        if (localHideUndated) count++
        if (localHidePriceless) count++
        if (localHideSeen) count++
        if (localOnlyDiscounted) count++
        if (sources.isNotEmpty() && localSelectedSources.size < sources.size) count++
        if (localAvailability.size < AvailabilityStatus.entries.size) count++
        count
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (alertsVm != null) "Filtri, Avvisi e Impostazioni" else "Filtri e Opzioni",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Chiudi", modifier = Modifier.size(16.dp))
            }
        }

        // 3 Segmented Tabs quando alertsVm è collegato: Filtri | Avvisi | Impostazioni
        if (alertsVm != null) {
            val alerts by alertsVm.alerts.collectAsStateWithLifecycle()
            val liveUnread = alerts.count { it.isUnread }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SheetTabButton(
                    label = if (activeFilterCount > 0) "Filtri ($activeFilterCount)" else "Filtri",
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.weight(1f),
                )
                SheetTabButton(
                    label = if (liveUnread > 0) "Avvisi ($liveUnread)" else "Avvisi",
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        alertsVm.refresh()
                    },
                    modifier = Modifier.weight(1f),
                )
                SheetTabButton(
                    label = "Impostazioni",
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        HorizontalDivider()

        when {
            alertsVm != null && selectedTab == 1 -> {
                val alerts by alertsVm.alerts.collectAsStateWithLifecycle()
                val isLoading by alertsVm.isLoading.collectAsStateWithLifecycle()
                AlertsPanelSection(
                    alerts = alerts,
                    isLoading = isLoading,
                    onMarkRead = alertsVm::markRead,
                    onMarkAllRead = alertsVm::markAllRead,
                    onOpenAlert = { alert ->
                        alertsVm.markRead(alert)
                        alert.itemKeyOrNull()?.let { key ->
                            onClose()
                            onOpenAlertItem?.invoke(key)
                        }
                    },
                    showMarkAllButton = true,
                    modifier = Modifier.weight(1f),
                )
            }

            alertsVm != null && selectedTab == 2 -> {
                val preferences by alertsVm.preferences.collectAsStateWithLifecycle()
                val context = LocalContext.current
                var permissionDeniedBanner by remember {
                    mutableStateOf(!NotificationHelper.canPostNotifications(context))
                }
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    permissionDeniedBanner = !granted
                    alertsVm.setBackgroundSyncEnabled(true)
                }

                SettingsPanelSection(
                    preferences = preferences,
                    showPermissionWarning = preferences.backgroundSyncEnabled &&
                        (permissionDeniedBanner || !NotificationHelper.canPostNotifications(context)),
                    onOpenSystemNotificationSettings = { openAppNotificationSettings(context) },
                    onSendTestNotification = { sendTestNotification(context) },
                    onToggleBackgroundSync = { enabled ->
                        if (enabled &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !NotificationHelper.canPostNotifications(context)
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            permissionDeniedBanner = !NotificationHelper.canPostNotifications(context)
                            alertsVm.setBackgroundSyncEnabled(enabled)
                        }
                    },
                    onSelectSyncInterval = alertsVm::setSyncIntervalHours,
                    onToggleSyncWifiOnly = alertsVm::setSyncWifiOnly,
                    onSelectThemeMode = alertsVm::setThemeMode,
                    onToggleDynamicColors = alertsVm::setDynamicColors,
                    onToggleBackInStock = alertsVm::setNotifyBackInStock,
                    onTogglePriceDrop = alertsVm::setNotifyPriceDrop,
                    onToggleNewExpansion = alertsVm::setNotifyNewExpansion,
                    onToggleNewsDigest = alertsVm::setNotifyNewsDigest,
                    ignoredCount = ignoredCount,
                    onManageIgnored = onManageIgnored,
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                )
            }

            else -> {
                // TAB 0: FILTRI E ORDINAMENTO
                // Sezione 1: Ordina per
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Ordina per",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        ListingSortOption.entries.forEach { option ->
                            FilterChip(
                                selected = localSortOption == option,
                                onClick = {
                                    localSortOption = option
                                    onSortOptionChange?.invoke(option)
                                },
                                label = {
                                    Text(
                                        text = option.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                },
                            )
                        }
                    }
                }

                HorizontalDivider()

                // Sezione 2: Filtri rapidi e Nascondi (non chiude il drawer al tocco!)
                HideFilterChipsSection(
                    hideUndated = localHideUndated,
                    hidePriceless = localHidePriceless,
                    hideSeen = localHideSeen,
                    onlyDiscounted = localOnlyDiscounted,
                    onToggleUndated = { localHideUndated = !localHideUndated },
                    onTogglePriceless = { localHidePriceless = !localHidePriceless },
                    onToggleSeen = { localHideSeen = !localHideSeen },
                    onToggleDiscounted = { localOnlyDiscounted = !localOnlyDiscounted },
                )

                HorizontalDivider()

                // Sezione 3: Disponibilità (header allineato come Negozi)
                val allAvailability = remember { AvailabilityStatus.entries.toSet() }
                val availabilityOptions = remember {
                    listOf(
                        AvailabilityStatus.AVAILABLE to "Disponibile",
                        AvailabilityStatus.PREORDER to "Preordine",
                        AvailabilityStatus.BACKORDER to "Su richiesta",
                        AvailabilityStatus.UNAVAILABLE to "Non disponibile",
                        AvailabilityStatus.DISCONTINUED to "Fuori produz.",
                        AvailabilityStatus.UNKNOWN to "Da verificare",
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Disponibilità (${localAvailability.size}/${allAvailability.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    SelectToggleButtonsHorizontal(
                        onSelectAll = { localAvailability = allAvailability.toMutableSet() },
                        onDeselectAll = { localAvailability = mutableSetOf() },
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    availabilityOptions.chunked(2).forEach { rowOptions ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            rowOptions.forEach { (status, label) ->
                                val isSelected = status in localAvailability
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        val next = localAvailability.toMutableSet()
                                        if (isSelected) next.remove(status) else next.add(status)
                                        localAvailability = next
                                    },
                                    label = {
                                        Text(
                                            text = label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 11.sp,
                                        )
                                    },
                                    leadingIcon = if (isSelected) {
                                        {
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(12.dp),
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Sezione 4: Negozi
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Negozi (${localSelectedSources.size}/${sources.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    SelectToggleButtonsHorizontal(
                        onSelectAll = { localSelectedSources = sources.map { it.id }.toMutableSet() },
                        onDeselectAll = { localSelectedSources = mutableSetOf() },
                    )
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    items(sources.chunked(2), key = { row -> row.first().id }) { rowSources ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            rowSources.forEach { source ->
                                val isChecked = localSelectedSources.contains(source.id)
                                FilterChip(
                                    selected = isChecked,
                                    onClick = {
                                        val next = localSelectedSources.toMutableSet()
                                        if (isChecked) next.remove(source.id) else next.add(source.id)
                                        localSelectedSources = next
                                    },
                                    label = {
                                        Text(
                                            text = source.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    },
                                    leadingIcon = if (isChecked) {
                                        {
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(12.dp),
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (rowSources.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                if ((alertsVm == null && onOpenAlerts != null) || onManageIgnored != null) {
                    HorizontalDivider()
                }

                if (alertsVm == null && onOpenAlerts != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (unreadAlertCount > 0) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenAlerts),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Notifications,
                                contentDescription = null,
                                tint = if (unreadAlertCount > 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = if (unreadAlertCount > 0) {
                                    "Avvisi e Notifiche ($unreadAlertCount)"
                                } else {
                                    "Avvisi e Notifiche"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                if (onManageIgnored != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onManageIgnored),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = BlockVector,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = if (ignoredCount > 0) {
                                    "Gestisci giochi ignorati ($ignoredCount)"
                                } else {
                                    "Gestisci giochi ignorati"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onReset,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        Text("Azzera", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            onApply(
                                sourcesForApply(),
                                localHideUndated,
                                localHidePriceless,
                                localHideSeen,
                                localOnlyDiscounted,
                                localAvailability.toSet(),
                                localSortOption,
                            )
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        Text("Applica", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectToggleButtonsHorizontal(
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        SelectToggleSquare(
            icon = Icons.Filled.Check,
            contentDescription = "Seleziona Tutti",
            tint = MaterialTheme.colorScheme.primary,
            onClick = onSelectAll,
        )
        SelectToggleSquare(
            icon = Icons.Filled.Close,
            contentDescription = "Deseleziona Tutti",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onDeselectAll,
        )
    }
}

@Composable
private fun SelectToggleSquare(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = Modifier
            .size(26.dp)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
