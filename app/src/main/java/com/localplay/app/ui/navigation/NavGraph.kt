package com.localplay.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.localplay.app.feature.detail.DetailScreen
import com.localplay.app.feature.home.FolderVideosScreen
import com.localplay.app.feature.home.HomeScreen
import com.localplay.app.feature.permission.PermissionGate
import com.localplay.app.feature.player.PlayerScreen
import com.localplay.app.feature.settings.SettingsScreen
import com.localplay.app.feature.sniff.SniffScreen
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object Routes {
    const val HOME = "home"
    const val FOLDER = "folder/{folderKey}"
    const val SNIFF = "sniff"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{path}"
    const val PLAYER = "player/{path}?fromStart={fromStart}"

    fun folder(folderKey: String): String =
        "folder/${URLEncoder.encode(folderKey, StandardCharsets.UTF_8.name())}"

    fun detail(path: String): String =
        "detail/${URLEncoder.encode(path, StandardCharsets.UTF_8.name())}"

    fun player(path: String, fromStart: Boolean): String =
        "player/${URLEncoder.encode(path, StandardCharsets.UTF_8.name())}?fromStart=$fromStart"
}

@Composable
fun LocalPlayNavHost() {
    var permissionGranted by remember { mutableStateOf(false) }
    val navController = rememberNavController()

    PermissionGate(
        onGranted = { permissionGranted = true }
    ) {
        if (!permissionGranted) return@PermissionGate

        NavHost(navController = navController, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenFolder = { key -> navController.navigate(Routes.folder(key)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenSniff = { navController.navigate(Routes.SNIFF) }
                )
            }
            composable(Routes.SNIFF) {
                SniffScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPlayer = { path ->
                        navController.navigate(Routes.player(path, fromStart = true))
                    }
                )
            }
            composable(
                route = Routes.FOLDER,
                arguments = listOf(navArgument("folderKey") { type = NavType.StringType })
            ) { entry ->
                val encoded = entry.arguments?.getString("folderKey").orEmpty()
                val folderKey = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
                FolderVideosScreen(
                    folderKey = folderKey,
                    onBack = { navController.popBackStack() },
                    onOpenPlayer = { path, fromStart ->
                        navController.navigate(Routes.player(path, fromStart))
                    },
                    onOpenDetail = { path -> navController.navigate(Routes.detail(path)) }
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument("path") { type = NavType.StringType })
            ) { entry ->
                val encoded = entry.arguments?.getString("path").orEmpty()
                val path = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
                DetailScreen(path = path, onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.PLAYER,
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType },
                    navArgument("fromStart") {
                        type = NavType.BoolType
                        defaultValue = false
                    }
                )
            ) { entry ->
                val encoded = entry.arguments?.getString("path").orEmpty()
                val path = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
                val fromStart = entry.arguments?.getBoolean("fromStart") ?: false
                PlayerScreen(
                    path = path,
                    fromStart = fromStart,
                    onBack = { navController.popBackStack() },
                    onOpenSniff = { navController.navigate(Routes.SNIFF) },
                    onOpenPlayer = { playPath ->
                        navController.navigate(Routes.player(playPath, fromStart = true)) {
                            launchSingleTop = true
                        }
                    }
                )
            }
        }
    }
}
