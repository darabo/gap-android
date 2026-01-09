package com.gapmesh.droid.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gapmesh.droid.R

/**
 * Navigation tabs for the bottom navigation bar
 */
enum class BottomNavTab(
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val labelResId: Int
) {
    MESH(Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat, R.string.nav_mesh),
    LOCATION(Icons.Filled.LocationOn, Icons.Outlined.LocationOn, R.string.nav_location),
    PEOPLE(Icons.Filled.People, Icons.Outlined.People, R.string.nav_people),
    SETTINGS(Icons.Filled.Settings, Icons.Outlined.Settings, R.string.nav_settings)
}

/**
 * Modern bottom navigation bar for Gap Mesh
 * Provides clear, accessible navigation between main app sections
 */
@Composable
fun GapMeshBottomNavigation(
    currentTab: BottomNavTab,
    onTabSelected: (BottomNavTab) -> Unit,
    unreadMeshCount: Int = 0,
    unreadLocationCount: Int = 0,
    peopleNearbyCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    
    NavigationBar(
        modifier = modifier,
        containerColor = colorScheme.surface,
        contentColor = colorScheme.onSurface,
        tonalElevation = 3.dp
    ) {
        BottomNavTab.entries.forEach { tab ->
            val selected = currentTab == tab
            val badgeCount = when (tab) {
                BottomNavTab.MESH -> unreadMeshCount
                BottomNavTab.LOCATION -> unreadLocationCount
                BottomNavTab.PEOPLE -> peopleNearbyCount
                BottomNavTab.SETTINGS -> 0
            }
            
            NavigationBarItem(
                selected = selected,
                onClick = { onTabSelected(tab) },
                icon = {
                    if (badgeCount > 0) {
                        BadgedBox(
                            badge = {
                                Badge(
                                    containerColor = Color(0xFF2E7D32)
                                ) {
                                    Text(
                                        text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                                        color = Color.White
                                    )
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = stringResource(tab.labelResId),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    } else {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                            contentDescription = stringResource(tab.labelResId),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                label = {
                    Text(
                        text = stringResource(tab.labelResId),
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF2E7D32),
                    selectedTextColor = Color(0xFF2E7D32),
                    unselectedIconColor = colorScheme.onSurfaceVariant,
                    unselectedTextColor = colorScheme.onSurfaceVariant,
                    indicatorColor = Color(0xFF2E7D32).copy(alpha = 0.1f)
                )
            )
        }
    }
}
