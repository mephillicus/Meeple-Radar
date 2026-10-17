package com.meepleradar.ui.nav

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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.meepleradar.AppContainer
import com.meepleradar.ui.components.EmptyView
import com.meepleradar.ui.incoming.IncomingScreen
import com.meepleradar.ui.incoming.IncomingViewModel

const val ROUTE_INCOMING = "incoming"
const val ROUTE_FAVORITES = "favorites"

@Composable
fun MeepleRadarNav(container: AppContainer, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
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
                IncomingScreen(vm = vm, onOpen = { })
            }

            composable(ROUTE_FAVORITES) {
                EmptyView("Preferiti in arrivo")
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
