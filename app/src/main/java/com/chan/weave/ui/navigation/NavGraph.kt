package com.chan.weave.ui.navigation

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.chan.weave.ui.MainViewModel
import com.chan.weave.ui.screens.AppsScreen
import com.chan.weave.ui.screens.DnsScreen
import com.chan.weave.ui.screens.HomeScreen
import com.chan.weave.ui.screens.NodesScreen
import com.chan.weave.ui.screens.RoutingScreen
import com.chan.weave.ui.screens.SettingsScreen
import com.chan.weave.ui.theme.AuroraCyan
import com.chan.weave.ui.theme.InkSecondary
import com.chan.weave.ui.theme.NightOutline
import com.chan.weave.ui.theme.NightSurface

sealed class Dest(
    val route: String,
    val filled: ImageVector,
    val outlined: ImageVector
) {
    data object Home : Dest("home", Icons.Filled.Home, Icons.Outlined.Home)
    data object Nodes : Dest("nodes", Icons.Filled.WifiTethering, Icons.Outlined.WifiTethering)
    data object Settings : Dest("settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

private val TABS = listOf(Dest.Home, Dest.Nodes, Dest.Settings)

/**
 * Root navigation with a floating "dock" bottom bar —
 * frosted capsule, glowing active orb.
 */
@Composable
fun CianCoreNavGraph(
    vm: MainViewModel,
    requestVpnPermission: (Intent) -> Unit
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val showDock = current in TABS.map { it.route }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (showDock) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 48.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(32.dp))
                        .background(NightSurface.copy(alpha = 0.88f))
                        .border(1.dp, NightOutline, RoundedCornerShape(32.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TABS.forEach { dest ->
                        DockItem(
                            dest = dest,
                            active = current == dest.route,
                            onClick = {
                                if (current != dest.route) {
                                    nav.navigate(dest.route) {
                                        popUpTo(Dest.Home.route)
                                        launchSingleTop = true
                                    }
                                }
                            }
                        )
                    }
                }
            }
            }
        }
    ) { _ ->
        NavHost(
            navController = nav,
            startDestination = Dest.Home.route,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(Dest.Home.route) { HomeScreen(vm, requestVpnPermission) }
            composable(Dest.Nodes.route) { NodesScreen(vm) }
            composable(Dest.Settings.route) { SettingsScreen(onNavigate = { nav.navigate(it) }) }
            composable("apps") { AppsScreen(onBack = { nav.popBackStack() }) }
            composable("dns") { DnsScreen() }
        }
    }
}

@Composable
private fun DockItem(
    dest: Dest,
    active: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (active) 1.12f else 1f,
        animationSpec = tween(300),
        label = "dockScale"
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(56.dp)
            .scale(scale)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .blur(12.dp)
                    .background(AuroraCyan.copy(alpha = 0.35f), CircleShape)
            )
        }
        Icon(
            imageVector = if (active) dest.filled else dest.outlined,
            contentDescription = dest.route,
            tint = if (active) AuroraCyan else InkSecondary,
            modifier = Modifier.size(26.dp)
        )
    }
}

