package com.locality.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.locality.mobile.player.PlayerActivity
import com.locality.mobile.ui.screens.AccountScreen
import com.locality.mobile.ui.screens.CollectionScreen
import com.locality.mobile.ui.screens.CollectionsScreen
import com.locality.mobile.ui.screens.ConnectScreen
import com.locality.mobile.ui.screens.PartyScreen
import com.locality.mobile.ui.screens.WelcomeScreen
import com.locality.mobile.ui.screens.DownloadsScreen
import com.locality.mobile.ui.screens.HomeScreen
import com.locality.mobile.ui.screens.ItemScreen
import com.locality.mobile.ui.screens.LibrariesScreen
import com.locality.mobile.ui.screens.GalleryScreen
import com.locality.mobile.ui.screens.LibraryScreen
import com.locality.mobile.ui.screens.PersonScreen
import com.locality.mobile.ui.screens.SettingsScreen
import com.locality.mobile.ui.theme.LocalityTheme
import com.locality.mobile.ui.theme.Palette
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as LocalityApp).container
        setContent {
            LocalityTheme {
                AppNav(container)
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val TABS = listOf(
    Tab("home", "Home", Icons.Filled.Home),
    Tab("libraries", "Libraries", Icons.Filled.VideoLibrary),
    Tab("party", "Party", Icons.Filled.Groups),
    Tab("downloads", "Downloads", Icons.Filled.Download),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
private fun AppNav(container: AppContainer) {
    val nav: NavHostController = rememberNavController()
    val config by container.configStore.config.collectAsState(initial = UNSET)
    val session by container.cloud.session.collectAsState(initial = UNKNOWN_SESSION)

    fun playFile(fileId: Long) {
        val ctx = nav.context
        ctx.startActivity(PlayerActivity.intent(ctx, fileId))
    }

    // Wait for BOTH stores before picking the first screen: deciding early would
    // flash the welcome screen at someone who is already signed in.
    if (config === UNSET || session === UNKNOWN_SESSION) return

    // An account is enough to get in — parties and downloads work with no server
    // at all, so only a completely fresh install starts at the welcome screen.
    val start = when {
        config != null -> "home"
        session != null -> "home"
        else -> "welcome"
    }

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        containerColor = Palette.Bg,
        bottomBar = {
            if (TABS.any { it.route == currentRoute }) {
                NavigationBar(containerColor = Palette.Surface) {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = Palette.Accent,
                                selectedIconColor = Palette.AccentText,
                                selectedTextColor = Palette.Heading,
                                unselectedIconColor = Palette.Muted,
                                unselectedTextColor = Palette.Muted,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        AppNavHost(container, nav, ::playFile, start = start, modifier = Modifier.padding(innerPadding))
    }
}

@Composable
private fun AppNavHost(
    container: AppContainer,
    nav: NavHostController,
    playFile: (Long) -> Unit,
    start: String,
    modifier: Modifier,
) {
    NavHost(
        navController = nav,
        startDestination = start,
        modifier = modifier,
    ) {
        composable("welcome") {
            WelcomeScreen(
                cloud = container.cloud,
                store = container.configStore,
                party = container.party,
                onDone = { nav.navigate("home") { popUpTo("welcome") { inclusive = true } } },
                onEnterAddress = { nav.navigate("connect") },
            )
        }
        composable("connect") {
            ConnectScreen(
                store = container.configStore,
                repo = container.repo,
                onConnected = { nav.navigate("home") { popUpTo("connect") { inclusive = true } } },
                onSkip = { nav.navigate("downloads") },
            )
        }
        composable("home") {
            HomeScreen(
                repo = container.repo,
                onOpenItem = { nav.navigate("item/$it") },
                onOpenLibrary = { id, name, type -> nav.navigate("library/$id/${URLEncoder.encode(name, "UTF-8")}/$type") },
                onOpenCollections = { nav.navigate("collections") },
                onOpenDownloads = { nav.navigate("downloads") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenParty = { nav.navigate("party") },
                onPlayFile = playFile,
            )
        }
        composable("libraries") {
            LibrariesScreen(
                repo = container.repo,
                onOpenLibrary = { id, name, type -> nav.navigate("library/$id/${URLEncoder.encode(name, "UTF-8")}/$type") },
                onOpenCollections = { nav.navigate("collections") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenDownloads = { nav.navigate("downloads") },
                onOpenParty = { nav.navigate("party") },
            )
        }
        composable("library/{id}/{name}/{type}") { entry ->
            val id = entry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
            val name = URLDecoder.decode(entry.arguments?.getString("name") ?: "", "UTF-8")
            val type = entry.arguments?.getString("type") ?: "movies"
            if (type == "gallery") {
                GalleryScreen(
                    repo = container.repo,
                    libraryId = id,
                    libraryName = name,
                    onBack = { nav.popBackStack() },
                )
            } else {
                // Movies, TV and "Other" all list media_items and play the same way.
                LibraryScreen(
                    repo = container.repo,
                    libraryId = id,
                    libraryName = name,
                    onBack = { nav.popBackStack() },
                    onOpenItem = { nav.navigate("item/$it") },
                )
            }
        }
        composable("item/{id}") { entry ->
            val id = entry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
            ItemScreen(
                repo = container.repo,
                downloads = container.downloads,
                itemId = id,
                onBack = { nav.popBackStack() },
                onOpenItem = { nav.navigate("item/$it") },
                onOpenPerson = { nav.navigate("person/$it") },
                onOpenCollection = { nav.navigate("collection/${URLEncoder.encode(it, "UTF-8")}") },
                onPlayFile = playFile,
                party = container.party,
                onPartyStarted = { nav.navigate("party") },
            )
        }
        composable("person/{tmdbId}") { entry ->
            val id = entry.arguments?.getString("tmdbId")?.toLongOrNull() ?: return@composable
            PersonScreen(
                repo = container.repo,
                tmdbId = id,
                onBack = { nav.popBackStack() },
                onOpenItem = { nav.navigate("item/$it") },
            )
        }
        composable("collections") {
            CollectionsScreen(
                repo = container.repo,
                onBack = { nav.popBackStack() },
                onOpenCollection = { nav.navigate("collection/${URLEncoder.encode(it, "UTF-8")}") },
            )
        }
        composable("collection/{key}") { entry ->
            val key = URLDecoder.decode(entry.arguments?.getString("key") ?: "", "UTF-8")
            CollectionScreen(
                repo = container.repo,
                collectionKey = key,
                onBack = { nav.popBackStack() },
                onOpenItem = { nav.navigate("item/$it") },
            )
        }
        composable("downloads") {
            DownloadsScreen(
                db = container.db,
                downloads = container.downloads,
                imageUrl = container.repo::imageUrl,
                onBack = { nav.popBackStack() },
                onPlayLocal = playFile,
            )
        }
        composable("party") {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            PartyScreen(
                party = container.party,
                onSignIn = { nav.navigate("account") },
                onPlayParty = { ctx.startActivity(PlayerActivity.partyIntent(ctx)) },
            )
        }
        composable("account") {
            AccountScreen(
                cloud = container.cloud,
                store = container.configStore,
                party = container.party,
                progress = container.cloudProgress,
                onBack = { nav.popBackStack() },
                onConnected = { nav.navigate("home") { popUpTo("home") { inclusive = true } } },
            )
        }
        composable("settings") {
            SettingsScreen(
                store = container.configStore,
                repo = container.repo,
                onBack = { nav.popBackStack() },
                onOpenAccount = { nav.navigate("account") },
            )
        }
    }
}

/** Sentinel distinguishing "DataStore not read yet" from "no server saved". */
private val UNSET = com.locality.mobile.data.ServerConfig(" unset", 0, "")
