package io.github.abe_ma.headachediary.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import io.github.abe_ma.headachediary.R
import io.github.abe_ma.headachediary.ui.edit.AttackEditScreen
import io.github.abe_ma.headachediary.ui.edit.AttackEditViewModel
import io.github.abe_ma.headachediary.ui.records.RecordListScreen

object Routes {
    const val RECORDS = "records"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val ATTACK = "attack/{${AttackEditViewModel.ARG_ID}}"
    fun attack(id: Long) = "attack/$id"

    /** 通知タップ (効果リマインダー) から開く用。T-08 が PendingIntent の data に使う。 */
    fun attackDeepLink(id: Long) = "headachediary://attack/$id"
    const val ATTACK_DEEP_LINK = "headachediary://attack/{${AttackEditViewModel.ARG_ID}}"
}

private data class TopLevelTab(val route: String, val labelRes: Int, val icon: ImageVector)

private val tabs = listOf(
    TopLevelTab(Routes.RECORDS, R.string.tab_record, Icons.Filled.Edit),
    TopLevelTab(Routes.STATS, R.string.tab_stats, Icons.Filled.Info),
    TopLevelTab(Routes.SETTINGS, R.string.tab_settings, Icons.Filled.Settings),
)

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBottomBar = tabs.any { tab -> destination?.hierarchy?.any { it.route == tab.route } == true }

    Scaffold(
        // 上端・左右の WindowInsets は各画面の Scaffold/TopAppBar が処理する。ここでは下部タブ分だけ確保し二重適用を避ける。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = destination?.hierarchy?.any { it.route == tab.route } == true,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = Routes.RECORDS, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable(Routes.RECORDS) {
                RecordListScreen(
                    onAddAttack = { navController.navigate(Routes.attack(0L)) },
                    onOpenAttack = { navController.navigate(Routes.attack(it)) },
                )
            }
            // 集計 (T-06)・設定 (T-07) が置き換える。
            composable(Routes.STATS) { ComingSoon() }
            composable(Routes.SETTINGS) { ComingSoon() }
            composable(
                Routes.ATTACK,
                arguments = listOf(navArgument(AttackEditViewModel.ARG_ID) { type = NavType.LongType }),
                deepLinks = listOf(navDeepLink { uriPattern = Routes.ATTACK_DEEP_LINK }),
            ) {
                AttackEditScreen(onClose = { navController.popBackStack() })
            }
        }
    }
}

@Composable
private fun ComingSoon() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.placeholder_coming_soon))
    }
}
