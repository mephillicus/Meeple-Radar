package com.meepleradar.ui.nav

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import com.meepleradar.ui.detail.DetailScreen
import com.meepleradar.ui.detail.DetailViewModel
import com.meepleradar.ui.favorites.FavoritesScreen
import com.meepleradar.ui.favorites.FavoritesViewModel
import com.meepleradar.ui.incoming.IncomingScreen
import com.meepleradar.ui.incoming.IncomingViewModel

const val ROUTE_INCOMING = "incoming"
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
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == ROUTE_INCOMING,
                        onClick = { navController.switchTab(ROUTE_INCOMING) },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("In arrivo") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == ROUTE_FAVORITES,
                        onClick = { navController.switchTab(ROUTE_FAVORITES) },
                        icon = { Icon(Icons.Filled.Favorite, contentDescription = null) },
                        label = { Text("Preferiti") },
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

private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
