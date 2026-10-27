package com.meepleradar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

val MeepleVector: ImageVector = ImageVector.Builder(
    name = "Meeple",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 512f,
    viewportHeight = 512f,
).run {
    path(
        fill = SolidColor(Color.White),
        stroke = null,
    ) {
        moveTo(256f, 54.99f)
        curveTo(229f, 54.99f, 209.582f, 69.277f, 198.367f, 87.22f)
        curveTo(188.337f, 103.267f, 184.164f, 121.88f, 183.35f, 138.182f)
        curveTo(152.742f, 153.317f, 118.835f, 168.576f, 91.535f, 184.176f)
        curveTo(77.215f, 192.359f, 64.73f, 200.59f, 55.332f, 209.436f)
        curveTo(45.934f, 218.28f, 39f, 228.24f, 39f, 239.99f)
        curveTo(39f, 244.99f, 41.44f, 249.065f, 44.19f, 252.055f)
        curveTo(46.944f, 255.045f, 50.244f, 257.367f, 54.002f, 259.535f)
        curveTo(61.517f, 263.871f, 70.992f, 267.485f, 81.414f, 270.611f)
        curveTo(96.897f, 275.257f, 114.237f, 278.71f, 129.314f, 280.187f)
        curveTo(114.318f, 306.027f, 94.361f, 329.761f, 76.867f, 352.502f)
        curveTo(56.65f, 378.785f, 39f, 403.99f, 39f, 431.99f)
        curveTo(39f, 435.99f, 38.956f, 439.113f, 39.31f, 442.25f)
        curveTo(39.665f, 445.387f, 40.566f, 449.303f, 43.72f, 452.406f)
        curveTo(46.875f, 455.51f, 50.737f, 456.344f, 53.883f, 456.686f)
        curveTo(57.029f, 457.031f, 60.198f, 456.99f, 64.263f, 456.99f)
        horizontalLineTo(175.805f)
        curveTo(183.902f, 456.99f, 189.831f, 457.482f, 195.93f, 453.56f)
        curveTo(202.03f, 449.64f, 204.254f, 444.285f, 208.6f, 436.285f)
        lineTo(208.688f, 436.125f)
        lineTo(208.768f, 435.959f)
        curveTo(218.491f, 416.181f, 230.491f, 396.191f, 235.968f, 389.269f)
        curveTo(238.708f, 385.809f, 241.272f, 383.065f, 243.118f, 381.515f)
        curveTo(243.682f, 381.043f, 243.938f, 380.955f, 244.302f, 380.755f)
        curveTo(244.665f, 380.955f, 244.922f, 381.043f, 245.486f, 381.515f)
        curveTo(247.332f, 383.065f, 249.896f, 385.809f, 252.636f, 389.269f)
        curveTo(258.113f, 396.191f, 264.41f, 405.959f, 270.188f, 415.767f)
        curveTo(281.788f, 435.385f, 291.512f, 455.154f, 291.512f, 455.154f)
        lineTo(291.592f, 455.319f)
        lineTo(291.68f, 455.479f)
        curveTo(296.026f, 463.479f, 298.23f, 468.802f, 304.29f, 472.733f)
        curveTo(303.348f, 476.663f, 309.264f, 476.183f, 317.247f, 476.183f)
        horizontalLineTo(448f)
        curveTo(452f, 476.183f, 455.12f, 476.226f, 458.244f, 475.879f)
        curveTo(461.367f, 475.532f, 465.242f, 474.669f, 468.364f, 471.547f)
        curveTo(471.484f, 468.425f, 472.348f, 464.55f, 472.694f, 461.427f)
        curveTo(473.042f, 458.305f, 473f, 455.183f, 473f, 451.183f)
        curveTo(473f, 423.183f, 455.35f, 397.978f, 435.133f, 371.695f)
        curveTo(417.64f, 348.955f, 397.683f, 325.221f, 382.686f, 299.38f)
        curveTo(397.763f, 297.902f, 415.103f, 294.45f, 430.586f, 289.804f)
        curveTo(441.008f, 286.679f, 450.483f, 283.064f, 457.998f, 278.729f)
        curveTo(461.756f, 276.561f, 465.056f, 274.239f, 467.808f, 271.249f)
        curveTo(470.561f, 268.259f, 473f, 264.184f, 473f, 259.184f)
        curveTo(473f, 247.434f, 466.066f, 237.474f, 456.668f, 228.63f)
        curveTo(447.27f, 219.784f, 434.785f, 211.553f, 420.465f, 203.37f)
        curveTo(393.165f, 187.77f, 359.258f, 172.51f, 328.65f, 157.376f)
        curveTo(327.836f, 141.076f, 323.662f, 122.461f, 313.633f, 106.414f)
        curveTo(302.418f, 88.476f, 283f, 74.19f, 256f, 74.19f)
        close()
    }
    build()
}

val ScreenContentInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

@Composable
fun LoadingView(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Caricamento in corso...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onRetry,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Riprova")
                }
            }
        }
    }
}

@Composable
fun EmptyView(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
fun ScreenHeader(
    title: String,
    onRefresh: () -> Unit,
    subtitle: String = "MEEPLE RADAR",
    timestampText: String? = null,
    titleContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            if (titleContent != null) {
                titleContent()
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            if (timestampText != null) {
                Text(
                    text = timestampText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            trailingContent?.invoke()
        }
    }
}

val NewsIconVector: ImageVector = ImageVector.Builder(
    name = "NewsIcon",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 512f,
    viewportHeight = 512f,
).run {
    path(
        fill = SolidColor(Color.White),
        stroke = SolidColor(Color(0xFFE2E8F0)),
        strokeLineWidth = 8f,
    ) {
        moveTo(80f, 16f)
        horizontalLineTo(432f)
        arcTo(64f, 64f, 0f, false, true, 496f, 80f)
        verticalLineTo(432f)
        arcTo(64f, 64f, 0f, false, true, 432f, 496f)
        horizontalLineTo(80f)
        arcTo(64f, 64f, 0f, false, true, 16f, 432f)
        verticalLineTo(80f)
        arcTo(64f, 64f, 0f, false, true, 80f, 16f)
        close()
    }
    path(
        fill = SolidColor(Color(0xFFB06543)),
    ) {
        moveTo(44f, 289f)
        horizontalLineTo(104f)
        verticalLineTo(356f)
        arcTo(14f, 14f, 0f, false, true, 90f, 370f)
        horizontalLineTo(58f)
        arcTo(14f, 14f, 0f, false, true, 44f, 356f)
        close()
        moveTo(408f, 289f)
        horizontalLineTo(468f)
        verticalLineTo(356f)
        arcTo(14f, 14f, 0f, false, true, 454f, 370f)
        horizontalLineTo(422f)
        arcTo(14f, 14f, 0f, false, true, 408f, 356f)
        close()
    }
    path(
        fill = SolidColor(Color(0xFFE28965)),
    ) {
        moveTo(164f, 260f)
        horizontalLineTo(348f)
        arcTo(14f, 14f, 0f, false, true, 362f, 274f)
        verticalLineTo(274f)
        arcTo(14f, 14f, 0f, false, true, 348f, 288f)
        horizontalLineTo(164f)
        arcTo(14f, 14f, 0f, false, true, 150f, 274f)
        verticalLineTo(274f)
        arcTo(14f, 14f, 0f, false, true, 164f, 260f)
        close()
    }
    path(
        fill = SolidColor(Color(0xFFEF4444)),
    ) {
        moveTo(125f, 170f)
        curveTo(125f, 170f, 80f, 175f, 65f, 220f)
        verticalLineTo(270f)
        curveTo(95f, 275f, 125f, 270f, 135f, 255f)
        close()
    }
    path(
        fill = SolidColor(Color(0xFFFACC15)),
    ) {
        moveTo(390f, 175f)
        curveTo(390f, 175f, 435f, 178f, 448f, 220f)
        verticalLineTo(290f)
        curveTo(420f, 295f, 390f, 290f, 380f, 275f)
        close()
    }
    path(
        fill = SolidColor(Color(0xFFE11D48)),
    ) {
        moveTo(160f, 220f)
        horizontalLineTo(180f)
        lineTo(210f, 270f)
        verticalLineTo(220f)
        horizontalLineTo(225f)
        verticalLineTo(285f)
        horizontalLineTo(205f)
        lineTo(175f, 235f)
        verticalLineTo(285f)
        horizontalLineTo(160f)
        close()
    }
    build()
}
