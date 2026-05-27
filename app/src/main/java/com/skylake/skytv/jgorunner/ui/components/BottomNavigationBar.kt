package com.skylake.skytv.jgorunner.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun BottomNavigationBar(
    currentScreen: String,
    setCurrentScreen: (String) -> Unit
) {
    val items = listOf(
        BottomNavigationItem(
            title = "CloudHome",
            selectedIcon = Icons.Filled.Home,
            unselectedIcon = Icons.Outlined.Home,
            hasNews = false,
            label = "Home"
        ),
        BottomNavigationItem(
            title = "JioHome",
            selectedIcon = Icons.Filled.DeveloperMode,
            unselectedIcon = Icons.Outlined.DeveloperMode,
            hasNews = false,
            label = "Jio"
        ),
        BottomNavigationItem(
            title = "TataHome",
            selectedIcon = Icons.Filled.Public,
            unselectedIcon = Icons.Outlined.Public,
            hasNews = false,
            label = "Tata"
        ),
        BottomNavigationItem(
            title = "Settings",
            selectedIcon = Icons.Filled.Settings,
            unselectedIcon = Icons.Outlined.Settings,
            hasNews = false,
            label = "Settings"
        ),
        BottomNavigationItem(
            title = "Debug",
            selectedIcon = Icons.Filled.DeveloperMode,
            unselectedIcon = Icons.Outlined.DeveloperMode,
            hasNews = false,
            label = "Debug"
        ),
    )

    val selectedIndex = when (currentScreen) {
        "CloudHome", "CloudMain" -> 0
        "JioHome" -> 1
        "TataHome" -> 2
        "Settings", "SettingsTV" -> 3
        "Debug" -> 4
        else -> 0
    }

    val focusRequesters = remember { List(items.size) { FocusRequester() } }

    NavigationBar(
        modifier = Modifier.focusGroup()
    ) {
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                modifier = Modifier
                    .focusRequester(focusRequesters[index])
                    .focusable(),
                selected = selectedIndex == index,
                onClick = {
                    setCurrentScreen(item.title)
                },
                label = { Text(item.label) },
                icon = {
                    BadgedBox(badge = { if (item.hasNews) Badge() }) {
                        Icon(
                            imageVector = if (selectedIndex == index) item.selectedIcon else item.unselectedIcon,
                            contentDescription = item.label
                        )
                    }
                }
            )
        }
    }
}

data class BottomNavigationItem(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val hasNews: Boolean,
    val label: String
)