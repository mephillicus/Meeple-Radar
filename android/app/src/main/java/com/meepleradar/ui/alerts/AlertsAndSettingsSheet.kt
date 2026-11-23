package com.meepleradar.ui.alerts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meepleradar.BuildConfig
import com.meepleradar.data.local.AppThemeMode
import com.meepleradar.data.local.NotificationPreferences
import com.meepleradar.data.model.UserAlert
import com.meepleradar.data.model.UserAlertType
import com.meepleradar.data.repo.LocalCacheStats
import com.meepleradar.notifications.NotificationHelper
import com.meepleradar.notifications.PendingAlertNotification
import com.meepleradar.ui.components.BlockVector
import com.meepleradar.ui.theme.MeepleRadarTheme
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ALERT_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ITALIAN)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsAndSettingsSheet(
    vm: AlertsViewModel,
    onOpenItem: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val isLoading by vm.isLoading.collectAsStateWithLifecycle()
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    var permissionDeniedBanner by remember {
        mutableStateOf(!NotificationHelper.canPostNotifications(context))
    }

    LaunchedEffect(Unit) {
        vm.refreshDiagnostics()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionDeniedBanner = !granted
        vm.setBackgroundSyncEnabled(true)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        AlertsAndSettingsSheetContent(
            alerts = alerts,
            isLoading = isLoading,
            preferences = preferences,
            diagnostics = diagnostics,
            showPermissionWarning = preferences.backgroundSyncEnabled &&
                (permissionDeniedBanner || !NotificationHelper.canPostNotifications(context)),
            onOpenSystemNotificationSettings = { openAppNotificationSettings(context) },
            onSendTestNotification = { sendTestNotification(context) },
            onMarkRead = vm::markRead,
            onMarkAllRead = vm::markAllRead,
            onOpenAlert = { alert ->
                vm.markRead(alert)
                alert.itemKeyOrNull()?.let { key ->
                    onDismiss()
                    onOpenItem(key)
                }
            },
            onToggleBackgroundSync = { enabled ->
                if (enabled &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !NotificationHelper.canPostNotifications(context)
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    permissionDeniedBanner = !NotificationHelper.canPostNotifications(context)
                    vm.setBackgroundSyncEnabled(enabled)
                }
            },
            onSelectSyncInterval = vm::setSyncIntervalHours,
            onToggleSyncWifiOnly = vm::setSyncWifiOnly,
            onToggleQuietHours = vm::setQuietHoursEnabled,
            onSelectThemeMode = vm::setThemeMode,
            onToggleDynamicColors = vm::setDynamicColors,
            onToggleBackInStock = vm::setNotifyBackInStock,
            onTogglePriceDrop = vm::setNotifyPriceDrop,
            onSelectMinPriceDropPct = vm::setMinPriceDropPct,
            onToggleNewExpansion = vm::setNotifyNewExpansion,
            onToggleNewsDigest = vm::setNotifyNewsDigest,
            onForceFullResync = vm::forceFullResync,
            onClearMediaCache = vm::clearMediaCache,
            onExportFavorites = { vm.exportFavorites { text -> sharePlainText(context, text) } },
        )
    }
}

internal fun sharePlainText(context: Context, text: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "I miei preferiti su Meeple Radar")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(sendIntent, "Condividi lista preferiti").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}

internal fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

internal fun sendTestNotification(context: Context) {
    NotificationHelper.ensureChannel(context)
    if (NotificationHelper.canPostNotifications(context)) {
        NotificationHelper.postAlert(
            context = context,
            alert = PendingAlertNotification(
                alertId = 999_999L,
                title = "Notifica di prova — Meeple Radar",
                body = "Le notifiche sugli avvisi di prezzo, stock ed espansioni sono attive!",
                itemKey = null,
                alertType = UserAlertType.PRICE_DROP,
            ),
        )
    } else {
        openAppNotificationSettings(context)
    }
}

@Composable
internal fun AlertsAndSettingsSheetContent(
    alerts: List<UserAlert>,
    isLoading: Boolean,
    preferences: NotificationPreferences,
    onMarkRead: (UserAlert) -> Unit,
    onMarkAllRead: () -> Unit,
    onOpenAlert: (UserAlert) -> Unit,
    onToggleBackgroundSync: (Boolean) -> Unit,
    onToggleBackInStock: (Boolean) -> Unit,
    onTogglePriceDrop: (Boolean) -> Unit,
    onToggleNewExpansion: (Boolean) -> Unit,
    onToggleNewsDigest: (Boolean) -> Unit,
    onSelectSyncInterval: (Int) -> Unit = {},
    onToggleSyncWifiOnly: (Boolean) -> Unit = {},
    onToggleQuietHours: (Boolean) -> Unit = {},
    onSelectMinPriceDropPct: (Int) -> Unit = {},
    onSelectThemeMode: (AppThemeMode) -> Unit = {},
    onToggleDynamicColors: (Boolean) -> Unit = {},
    showPermissionWarning: Boolean = false,
    onOpenSystemNotificationSettings: () -> Unit = {},
    onSendTestNotification: () -> Unit = {},
    diagnostics: SettingsDiagnostics = SettingsDiagnostics(),
    onForceFullResync: () -> Unit = {},
    onClearMediaCache: () -> Unit = {},
    onExportFavorites: () -> Unit = {},
    initialTab: Int = 0,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }
    val unreadCount = alerts.count { it.isUnread }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "Avvisi e Impostazioni",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (selectedTab == 0 && unreadCount > 0) {
                TextButton(
                    onClick = onMarkAllRead,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "Segna letti",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // Segmented tabs: Avvisi (N) | Impostazioni
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SheetTabButton(
                label = if (unreadCount > 0) "Avvisi ($unreadCount)" else "Avvisi",
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                modifier = Modifier.weight(1f),
            )
            SheetTabButton(
                label = "Impostazioni",
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

        if (selectedTab == 0) {
            AlertsPanelSection(
                alerts = alerts,
                isLoading = isLoading,
                onMarkRead = onMarkRead,
                onMarkAllRead = onMarkAllRead,
                onOpenAlert = onOpenAlert,
                showMarkAllButton = false,
                modifier = Modifier.heightIn(max = 420.dp),
            )
        } else {
            SettingsPanelSection(
                preferences = preferences,
                diagnostics = diagnostics,
                showPermissionWarning = showPermissionWarning,
                onOpenSystemNotificationSettings = onOpenSystemNotificationSettings,
                onSendTestNotification = onSendTestNotification,
                onToggleBackgroundSync = onToggleBackgroundSync,
                onSelectSyncInterval = onSelectSyncInterval,
                onToggleSyncWifiOnly = onToggleSyncWifiOnly,
                onToggleQuietHours = onToggleQuietHours,
                onSelectThemeMode = onSelectThemeMode,
                onToggleDynamicColors = onToggleDynamicColors,
                onToggleBackInStock = onToggleBackInStock,
                onTogglePriceDrop = onTogglePriceDrop,
                onSelectMinPriceDropPct = onSelectMinPriceDropPct,
                onToggleNewExpansion = onToggleNewExpansion,
                onToggleNewsDigest = onToggleNewsDigest,
                onForceFullResync = onForceFullResync,
                onClearMediaCache = onClearMediaCache,
                onExportFavorites = onExportFavorites,
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
internal fun SheetTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier
            .height(36.dp)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
        }
    }
}

@Composable
internal fun AlertsPanelSection(
    alerts: List<UserAlert>,
    isLoading: Boolean,
    onMarkRead: (UserAlert) -> Unit,
    onMarkAllRead: () -> Unit,
    onOpenAlert: (UserAlert) -> Unit,
    showMarkAllButton: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var selectedTypeFilter by rememberSaveable { mutableStateOf<UserAlertType?>(null) }
    val filteredAlerts = remember(alerts, selectedTypeFilter) {
        if (selectedTypeFilter == null) alerts else alerts.filter { it.alertType == selectedTypeFilter }
    }
    val unreadCount = alerts.count { it.isUnread }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (alerts.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = selectedTypeFilter == null,
                        onClick = { selectedTypeFilter = null },
                        label = {
                            Text(
                                text = "Tutti (${alerts.size})",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 11.sp,
                            )
                        },
                    )
                    FilterChip(
                        selected = selectedTypeFilter == UserAlertType.PRICE_DROP,
                        onClick = {
                            selectedTypeFilter = if (selectedTypeFilter == UserAlertType.PRICE_DROP) null else UserAlertType.PRICE_DROP
                        },
                        label = {
                            Text(
                                text = "Cali prezzo",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 11.sp,
                            )
                        },
                    )
                    FilterChip(
                        selected = selectedTypeFilter == UserAlertType.BACK_IN_STOCK,
                        onClick = {
                            selectedTypeFilter = if (selectedTypeFilter == UserAlertType.BACK_IN_STOCK) null else UserAlertType.BACK_IN_STOCK
                        },
                        label = {
                            Text(
                                text = "Disponibili",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 11.sp,
                            )
                        },
                    )
                    FilterChip(
                        selected = selectedTypeFilter == UserAlertType.NEW_EXPANSION,
                        onClick = {
                            selectedTypeFilter = if (selectedTypeFilter == UserAlertType.NEW_EXPANSION) null else UserAlertType.NEW_EXPANSION
                        },
                        label = {
                            Text(
                                text = "Espansioni",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 11.sp,
                            )
                        },
                    )
                }

                if (showMarkAllButton && unreadCount > 0) {
                    TextButton(
                        onClick = onMarkAllRead,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "Segna letti",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        when {
            isLoading && alerts.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                }
            }

            filteredAlerts.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (alerts.isEmpty()) "Nessun avviso recente" else "Nessun avviso per questo filtro",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Qui appariranno cali di prezzo, rientri a magazzino dei preferiti e nuove espansioni.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredAlerts, key = { it.id }) { alert ->
                        AlertRowCard(
                            alert = alert,
                            onClick = {
                                if (alert.itemKeyOrNull() != null) {
                                    onOpenAlert(alert)
                                } else {
                                    onMarkRead(alert)
                                }
                            },
                            onOpen = { onOpenAlert(alert) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertRowCard(
    alert: UserAlert,
    onClick: () -> Unit,
    onOpen: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val (typeLabel, badgeBg, badgeFg) = when (alert.alertType) {
        UserAlertType.PRICE_DROP -> Triple(
            "Calo di prezzo",
            if (isDark) Color(0xFF14532D) else Color(0xFFDCFCE7),
            if (isDark) Color(0xFF86EFAC) else Color(0xFF15803D),
        )
        UserAlertType.BACK_IN_STOCK -> Triple(
            "Tornato disponibile",
            if (isDark) Color(0xFF1E3A8A) else Color(0xFFDBEAFE),
            if (isDark) Color(0xFF93C5FD) else Color(0xFF1D4ED8),
        )
        UserAlertType.NEW_EXPANSION -> Triple(
            "Nuova espansione",
            if (isDark) Color(0xFF78350F) else Color(0xFFFEF3C7),
            if (isDark) Color(0xFFFCD34D) else Color(0xFFB45309),
        )
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (alert.isUnread) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            },
        ),
        border = BorderStroke(
            1.dp,
            if (alert.isUnread) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (alert.isUnread) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(7.dp),
                        ) {}
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeBg,
                    ) {
                        Text(
                            text = typeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeFg,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        text = formatAlertTimestamp(alert.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text = alert.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (alert.isUnread) FontWeight.Bold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                alert.detailText?.takeIf(String::isNotBlank)?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (alert.itemKeyOrNull() != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.clickable(onClick = onOpen),
                ) {
                    Text(
                        text = "Apri ›",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingsPanelSection(
    preferences: NotificationPreferences,
    onToggleBackgroundSync: (Boolean) -> Unit,
    onToggleBackInStock: (Boolean) -> Unit,
    onTogglePriceDrop: (Boolean) -> Unit,
    onToggleNewExpansion: (Boolean) -> Unit,
    onToggleNewsDigest: (Boolean) -> Unit,
    onSelectSyncInterval: (Int) -> Unit = {},
    onToggleSyncWifiOnly: (Boolean) -> Unit = {},
    onToggleQuietHours: (Boolean) -> Unit = {},
    onSelectMinPriceDropPct: (Int) -> Unit = {},
    onSelectThemeMode: (AppThemeMode) -> Unit = {},
    onToggleDynamicColors: (Boolean) -> Unit = {},
    showPermissionWarning: Boolean = false,
    onOpenSystemNotificationSettings: () -> Unit = {},
    onSendTestNotification: () -> Unit = {},
    diagnostics: SettingsDiagnostics = SettingsDiagnostics(),
    onForceFullResync: () -> Unit = {},
    onClearMediaCache: () -> Unit = {},
    onExportFavorites: () -> Unit = {},
    ignoredCount: Int = 0,
    onManageIgnored: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Sezione Aspetto
        SettingsGroupCard(title = "Aspetto") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Tema applicazione",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AppThemeMode.entries.forEach { mode ->
                        val selected = preferences.themeMode == mode
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            border = BorderStroke(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clickable { onSelectThemeMode(mode) },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingToggleRow(
                        title = "Colori dinamici (Material You)",
                        subtitle = "Adatta la palette ai colori dello sfondo di Android 12+",
                        checked = preferences.dynamicColors,
                        enabled = true,
                        onCheckedChange = onToggleDynamicColors,
                    )
                }
            }
        }

        // 2. Sezione Sincronizzazione e Notifiche
        SettingsGroupCard(title = "Sincronizzazione e Notifiche") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showPermissionWarning) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenSystemNotificationSettings),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Permesso notifiche disattivato su Android",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "Attiva ›",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                SettingToggleRow(
                    title = "Sincronizzazione automatica",
                    subtitle = "Aggiorna il catalogo in background e invia gli avvisi selezionati",
                    checked = preferences.backgroundSyncEnabled,
                    enabled = true,
                    onCheckedChange = onToggleBackgroundSync,
                )

                if (preferences.backgroundSyncEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Frequenza sync",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(6, 12, 24).forEach { hours ->
                                val selected = preferences.syncIntervalHours == hours
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSelectSyncInterval(hours) },
                                    label = {
                                        Text(
                                            text = "${hours}h",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 11.sp,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    SettingToggleRow(
                        title = "Solo sotto rete Wi-Fi",
                        subtitle = "Evita il consumo dati mobili per il sync periodico",
                        checked = preferences.syncWifiOnly,
                        enabled = preferences.backgroundSyncEnabled,
                        onCheckedChange = onToggleSyncWifiOnly,
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    SettingToggleRow(
                        title = "Fascia silenziosa (22:00 – 08:00)",
                        subtitle = "Rimanda le notifiche notturne al ciclo del mattino",
                        checked = preferences.quietHoursEnabled,
                        enabled = preferences.backgroundSyncEnabled,
                        onCheckedChange = onToggleQuietHours,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                SettingToggleRow(
                    title = "Preferiti tornati disponibili",
                    subtitle = "Avvisa quando un gioco nei preferiti torna in stock",
                    checked = preferences.notifyBackInStock,
                    enabled = preferences.backgroundSyncEnabled,
                    onCheckedChange = onToggleBackInStock,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingToggleRow(
                    title = "Cali di prezzo sui preferiti",
                    subtitle = "Avvisa quando scende il prezzo o tocca un nuovo minimo",
                    checked = preferences.notifyPriceDrop,
                    enabled = preferences.backgroundSyncEnabled,
                    onCheckedChange = onTogglePriceDrop,
                )

                if (preferences.notifyPriceDrop) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Soglia minima calo prezzo",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = if (preferences.backgroundSyncEnabled) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            listOf(
                                0 to "Tutti",
                                10 to "≥ 10%",
                                20 to "≥ 20%",
                                30 to "≥ 30%",
                            ).forEach { (threshold, label) ->
                                val selected = preferences.minPriceDropPct == threshold
                                val enabled = preferences.backgroundSyncEnabled
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = when {
                                        selected && enabled -> MaterialTheme.colorScheme.primaryContainer
                                        selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                        else -> MaterialTheme.colorScheme.surface
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.45f)
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(30.dp)
                                        .clickable(enabled = enabled) { onSelectMinPriceDropPct(threshold) },
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 10.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            color = when {
                                                selected && enabled -> MaterialTheme.colorScheme.onPrimaryContainer
                                                enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                            },
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingToggleRow(
                    title = "Nuove espansioni in arrivo",
                    subtitle = "Avvisa quando viene rilevata un'espansione dei tuoi giochi",
                    checked = preferences.notifyNewExpansion,
                    enabled = preferences.backgroundSyncEnabled,
                    onCheckedChange = onToggleNewExpansion,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingToggleRow(
                    title = "Riepilogo novità giornaliere",
                    subtitle = "Notifica riassuntiva quando arrivano nuovi titoli nel catalogo",
                    checked = preferences.notifyNewsDigest,
                    enabled = preferences.backgroundSyncEnabled,
                    onCheckedChange = onToggleNewsDigest,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onSendTestNotification,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Notifications,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Invia notifica di prova",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        // 3. Sezione Catalogo e Preferiti
        SettingsGroupCard(title = "Catalogo e Preferiti") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onManageIgnored != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onManageIgnored),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = BlockVector,
                                contentDescription = null,
                                tint = if (ignoredCount > 0) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = if (ignoredCount > 0) {
                                    "Gestisci giochi ignorati ($ignoredCount)"
                                } else {
                                    "Gestisci giochi ignorati"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "Apri ›",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !diagnostics.isExportingFavorites, onClick = onExportFavorites),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (diagnostics.isExportingFavorites) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Share,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Esporta / Condividi Preferiti",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Invia titolo, miglior prezzo e link come testo",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = "Invia ›",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        // 4. Sezione Dati e Cache
        SettingsGroupCard(title = "Dati e Cache") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val stats = diagnostics.cacheStats
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "${stats.listingsCount} offerte · ${stats.gamesCount} giochi · ${stats.sourcesCount} negozi",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Ultimo sync completo: ${formatEpochMillis(stats.lastFullSyncAt.takeIf { it > 0L } ?: stats.lastModifiedTimestamp)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Cache immagini e HTTP: ${formatByteSize(diagnostics.mediaCacheSizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onClearMediaCache,
                        enabled = !diagnostics.isClearingCache && !diagnostics.isResyncing,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        if (diagnostics.isClearingCache) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "Svuota cache",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }

                    Button(
                        onClick = onForceFullResync,
                        enabled = !diagnostics.isResyncing && !diagnostics.isClearingCache,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        if (diagnostics.isResyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (diagnostics.isResyncing) "Sync…" else "Sync completo",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }

                diagnostics.statusMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (diagnostics.isStatusError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
        }

        // 5. Info app (footer compatto)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Meeple Radar v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Aggiornato: ${formatEpochMillis(diagnostics.cacheStats.lastModifiedTimestamp)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val bggLabel = diagnostics.bggUsername?.takeIf(String::isNotBlank)?.let { user ->
                        "BGG: $user (${diagnostics.bggGamesCount} giochi)"
                    } ?: "Collezione BGG non collegata"
                    Text(
                        text = bggLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(modifier = Modifier.padding(12.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                },
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                },
            )
        }
        Spacer(Modifier.width(4.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

private fun formatAlertTimestamp(iso: String): String {
    val instant = runCatching { OffsetDateTime.parse(iso).toInstant() }
        .recoverCatching { Instant.parse(iso) }
        .getOrNull() ?: return iso.take(10)
    val localDateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
    return localDateTime.format(ALERT_TIME_FORMATTER)
}

private fun formatEpochMillis(epochMs: Long): String {
    if (epochMs <= 0L) return "mai eseguito"
    val localDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
    return localDateTime.format(ALERT_TIME_FORMATTER)
}

private fun formatByteSize(bytes: Long): String = when {
    bytes <= 0L -> "0 KB"
    bytes < 1024L * 1024L -> "${(bytes / 1024L).coerceAtLeast(1L)} KB"
    else -> String.format(Locale.ITALY, "%.1f MB", bytes / (1024.0 * 1024.0))
}

@Preview(name = "Alerts Sheet — Light", showBackground = true)
@Preview(
    name = "Alerts Sheet — Dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AlertsSheetPreview() {
    val sampleAlerts = listOf(
        UserAlert(
            id = 1L,
            alertType = UserAlertType.PRICE_DROP,
            gameId = 42L,
            productId = null,
            listingId = 101L,
            title = "Ark Nova: Marine Worlds",
            detailText = "DungeonDice: 46,90 € → 39,90 € (-15%)",
            dedupeKey = "drop:101",
            readAt = null,
            createdAt = "2026-04-19T08:15:00Z",
        ),
        UserAlert(
            id = 2L,
            alertType = UserAlertType.BACK_IN_STOCK,
            gameId = 7L,
            productId = null,
            listingId = 202L,
            title = "Brass: Birmingham",
            detailText = "Tornato disponibile su Fantasia Store (59,90 €)",
            dedupeKey = "stock:202",
            readAt = "2026-04-18T12:00:00Z",
            createdAt = "2026-04-18T09:00:00Z",
        ),
    )
    MeepleRadarTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            AlertsAndSettingsSheetContent(
                alerts = sampleAlerts,
                isLoading = false,
                preferences = NotificationPreferences(backgroundSyncEnabled = true),
                onMarkRead = {},
                onMarkAllRead = {},
                onOpenAlert = {},
                onToggleBackgroundSync = {},
                onToggleBackInStock = {},
                onTogglePriceDrop = {},
                onToggleNewExpansion = {},
                onToggleNewsDigest = {},
                initialTab = 0,
            )
        }
    }
}

@Preview(name = "Settings Sheet — Light", showBackground = true)
@Preview(
    name = "Settings Sheet — Dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SettingsSheetPreview() {
    MeepleRadarTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            AlertsAndSettingsSheetContent(
                alerts = emptyList(),
                isLoading = false,
                preferences = NotificationPreferences(
                    backgroundSyncEnabled = true,
                    minPriceDropPct = 10,
                    quietHoursEnabled = true,
                ),
                diagnostics = SettingsDiagnostics(
                    cacheStats = LocalCacheStats(
                        listingsCount = 1420,
                        gamesCount = 612,
                        sourcesCount = 14,
                        lastModifiedTimestamp = 1_776_600_000_000L,
                        lastFullSyncAt = 1_776_580_000_000L,
                    ),
                    mediaCacheSizeBytes = 6L * 1024L * 1024L,
                    bggUsername = "zorak_it",
                    bggGamesCount = 87,
                ),
                onMarkRead = {},
                onMarkAllRead = {},
                onOpenAlert = {},
                onToggleBackgroundSync = {},
                onToggleBackInStock = {},
                onTogglePriceDrop = {},
                onToggleNewExpansion = {},
                onToggleNewsDigest = {},
                initialTab = 1,
            )
        }
    }
}
