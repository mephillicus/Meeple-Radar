package com.meepleradar.ui.nav

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.meepleradar.AppContainer
import com.meepleradar.ui.components.MeepleVector
import com.meepleradar.ui.detail.DetailScreen
import com.meepleradar.ui.detail.DetailViewModel
import com.meepleradar.ui.favorites.FavoritesScreen
import com.meepleradar.ui.favorites.FavoritesViewModel
import com.meepleradar.ui.incoming.IncomingScreen
import com.meepleradar.ui.incoming.IncomingViewModel
import com.meepleradar.ui.news.NewsScreen
import com.meepleradar.ui.news.NewsViewModel
import com.meepleradar.ui.preorders.PreordersScreen
import com.meepleradar.ui.preorders.PreordersViewModel

const val ROUTE_INCOMING = "incoming"
const val ROUTE_NEWS = "news"
const val ROUTE_PREORDERS = "preorders"
const val ROUTE_FAVORITES = "favorites"
const val ROUTE_DETAIL = "detail/{key}"

private const val ARG_KEY = "key"

private fun detailRoute(key: String): String = "detail/${Uri.encode(key)}"

@Composable
fun MeepleRadarNav(container: AppContainer, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (currentRoute != ROUTE_DETAIL) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentRoute == ROUTE_INCOMING,
                            onClick = { navController.switchTab(ROUTE_INCOMING) },
                            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                            label = { Text("Lista") },
                        )
                        val newlyAddedCount by container.repository.newlyAddedCountFlow().collectAsStateWithLifecycle(initialValue = container.repository.newlyAddedCount())
                        NavigationBarItem(
                            selected = currentRoute == ROUTE_NEWS,
                            onClick = { navController.switchTab(ROUTE_NEWS) },
                            icon = {
                                Box {
                                    Icon(MeepleVector, contentDescription = null, modifier = Modifier.size(24.dp))
                                    if (newlyAddedCount > 0) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .offset(x = 10.dp, y = (-4).dp),
                                        ) {
                                            Text(
                                                text = "$newlyAddedCount",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 9.sp,
                                                color = MaterialTheme.colorScheme.onTertiary,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                }
                            },
                            label = { Text("Novità") },
                        )
                        val preorderCount = container.repository.preorderCount()
                        NavigationBarItem(
                            selected = currentRoute == ROUTE_PREORDERS,
                            onClick = { navController.switchTab(ROUTE_PREORDERS) },
                            icon = {
                                Box {
                                    Icon(
                                        imageVector = MeepleVector,
                                        contentDescription = null,
                                        tint = Color(0xFF3B82F6),
                                        modifier = Modifier
                                            .size(24.dp)
                                            .blur(1.dp),
                                    )
                                    if (preorderCount > 0) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .offset(x = 10.dp, y = (-4).dp),
                                        ) {
                                            Text(
                                                text = "$preorderCount",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 9.sp,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                }
                            },
                            label = { Text("Preordini") },
                        )
                        NavigationBarItem(
                            selected = currentRoute == ROUTE_FAVORITES,
                            onClick = { navController.switchTab(ROUTE_FAVORITES) },
                            icon = { Icon(Icons.Filled.Favorite, contentDescription = null) },
                            label = { Text("Preferiti") },
                        )
                    }

                    Text(
                        text = "Aggiornato: ${formatTimestamp(container.repository.lastDbModifiedTimestamp())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        textAlign = TextAlign.Start,
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_INCOMING,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(ROUTE_INCOMING) {
                val vm: IncomingViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { IncomingViewModel(container.repository) }
                    },
                )
                IncomingScreen(
                    vm = vm,
                    onOpen = { item -> navController.navigate(detailRoute(item.key)) },
                    imageLookup = container.boardGameImageLookup,
                )
            }

            composable(ROUTE_NEWS) {
                val vm: NewsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { NewsViewModel(container.repository) }
                    },
                )
                NewsScreen(
                    vm = vm,
                    onOpen = { item -> navController.navigate(detailRoute(item.key)) },
                    imageLookup = container.boardGameImageLookup,
                )
            }

            composable(ROUTE_PREORDERS) {
                val vm: PreordersViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { PreordersViewModel(container.repository) }
                    },
                )
                PreordersScreen(
                    vm = vm,
                    onOpen = { item -> navController.navigate(detailRoute(item.key)) },
                    imageLookup = container.boardGameImageLookup,
                )
            }

            composable(ROUTE_FAVORITES) {
                val vm: FavoritesViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { FavoritesViewModel(container.repository) }
                    },
                )
                FavoritesScreen(
                    vm = vm,
                    onOpen = { item -> navController.navigate(detailRoute(item.key)) },
                    imageLookup = container.boardGameImageLookup,
                )
            }

            composable(
                route = ROUTE_DETAIL,
                arguments = listOf(navArgument(ARG_KEY) { type = NavType.StringType }),
            ) { entry ->
                val key = entry.arguments?.getString(ARG_KEY).orEmpty()
                val vm: DetailViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            DetailViewModel(
                                repo = container.repository,
                                key = key,
                                imageLookup = container.boardGameImageLookup,
                            )
                        }
                    },
                )
                DetailScreen(
                    vm = vm,
                    onBack = { navController.popBackStack() },
                    imageLookup = container.boardGameImageLookup,
                )
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return "in attesa..."
    val instant = java.time.Instant.ofEpochMilli(timestamp)
    val localDateTime = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
    val formatter = java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", java.util.Locale.ITALIAN)
    return localDateTime.format(formatter)
}

private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
