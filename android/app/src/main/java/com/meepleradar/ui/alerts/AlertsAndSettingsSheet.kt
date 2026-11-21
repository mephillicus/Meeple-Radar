package com.meepleradar.ui.alerts

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.meepleradar.data.local.NotificationPreferences
import com.meepleradar.data.model.UserAlert
import com.meepleradar.data.model.UserAlertType
import com.meepleradar.notifications.NotificationHelper
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
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
                    vm.setBackgroundSyncEnabled(enabled)
                }
            },
            onToggleBackInStock = vm::setNotifyBackInStock,
            onTogglePriceDrop = vm::setNotifyPriceDrop,
            onToggleNewExpansion = vm::setNotifyNewExpansion,
            onToggleNewsDigest = vm::setNotifyNewsDigest,
        )
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
                    text = "Avvisi e Notifiche",
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
            AlertsTabContent(
                alerts = alerts,
                isLoading = isLoading,
                onMarkRead = onMarkRead,
                onOpenAlert = onOpenAlert,
            )
        } else {
            SettingsTabContent(
                preferences = preferences,
                onToggleBackgroundSync = onToggleBackgroundSync,
                onToggleBackInStock = onToggleBackInStock,
                onTogglePriceDrop = onTogglePriceDrop,
                onToggleNewExpansion = onToggleNewExpansion,
                onToggleNewsDigest = onToggleNewsDigest,
            )
        }
    }
}

@Composable
private fun SheetTabButton(
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
            .height(38.dp)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun AlertsTabContent(
    alerts: List<UserAlert>,
    isLoading: Boolean,
    onMarkRead: (UserAlert) -> Unit,
    onOpenAlert: (UserAlert) -> Unit,
) {
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

        alerts.isEmpty() -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Nessun avviso recente",
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
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(alerts, key = { it.id }) { alert ->
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
private fun SettingsTabContent(
    preferences: NotificationPreferences,
    onToggleBackgroundSync: (Boolean) -> Unit,
    onToggleBackInStock: (Boolean) -> Unit,
    onTogglePriceDrop: (Boolean) -> Unit,
    onToggleNewExpansion: (Boolean) -> Unit,
    onToggleNewsDigest: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        ) {
            SettingToggleRow(
                title = "Sincronizzazione automatica (ogni 12h)",
                subtitle = "Aggiorna il catalogo in background con rete attiva e invia gli avvisi selezionati.",
                checked = preferences.backgroundSyncEnabled,
                enabled = true,
                onCheckedChange = onToggleBackgroundSync,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SettingToggleRow(
                title = "Preferiti tornati disponibili",
                subtitle = "Avvisa quando un gioco nei preferiti torna in stock",
                checked = preferences.notifyBackInStock,
                enabled = preferences.backgroundSyncEnabled,
                onCheckedChange = onToggleBackInStock,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SettingToggleRow(
                title = "Cali di prezzo sui preferiti",
                subtitle = "Avvisa quando scende il prezzo o tocca un nuovo minimo",
                checked = preferences.notifyPriceDrop,
                enabled = preferences.backgroundSyncEnabled,
                onCheckedChange = onTogglePriceDrop,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SettingToggleRow(
                title = "Nuove espansioni in arrivo",
                subtitle = "Avvisa quando viene rilevata un'espansione dei tuoi giochi",
                checked = preferences.notifyNewExpansion,
                enabled = preferences.backgroundSyncEnabled,
                onCheckedChange = onToggleNewExpansion,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SettingToggleRow(
                title = "Riepilogo novità giornaliere",
                subtitle = "Notifica riassuntiva quando arrivano nuovi titoli nel catalogo",
                checked = preferences.notifyNewsDigest,
                enabled = preferences.backgroundSyncEnabled,
                onCheckedChange = onToggleNewsDigest,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
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
                preferences = NotificationPreferences(backgroundSyncEnabled = true),
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
