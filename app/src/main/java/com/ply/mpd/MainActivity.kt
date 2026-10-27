package com.ply.mpd

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ply.mpd.data.models.HistoryEntry
import com.ply.mpd.data.storage.AppStorage
import com.ply.mpd.network.DownloadManager
import com.ply.mpd.network.FacebookExtractor
import com.ply.mpd.ui.screens.DownloadsScreen
import com.ply.mpd.ui.screens.HomeScreen
import com.ply.mpd.ui.screens.PlayerScreen
import com.ply.mpd.ui.screens.SettingsScreen
import com.ply.mpd.ui.theme.AccentPrimary
import com.ply.mpd.ui.theme.DarkBackground
import com.ply.mpd.ui.theme.DarkSurface
import com.ply.mpd.ui.theme.PlyTheme
import com.ply.mpd.ui.theme.TextPrimary
import com.ply.mpd.ui.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

sealed class Screen {
    object Home : Screen()
    object Downloads : Screen()
    object Settings : Screen()
    data class Player(val entry: HistoryEntry) : Screen()
}

class MainActivity : ComponentActivity() {

    private lateinit var appStorage: AppStorage
    private lateinit var downloadManager: DownloadManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appStorage = AppStorage.getInstance(this)
        downloadManager = DownloadManager.getInstance(this)

        setContent {
            PlyTheme {
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

                LaunchedEffect(intent) {
                    handleIntent(intent) { entry ->
                        currentScreen = Screen.Player(entry)
                    }
                }

                if (currentScreen is Screen.Player) {
                    val entry = (currentScreen as Screen.Player).entry
                    PlayerScreen(
                        entry = entry,
                        appStorage = appStorage,
                        downloadManager = downloadManager,
                        onBack = { currentScreen = Screen.Home }
                    )
                } else {
                    BackHandler(enabled = currentScreen != Screen.Home) {
                        currentScreen = Screen.Home
                    }

                    Scaffold(
                        containerColor = DarkBackground,
                        bottomBar = {
                            NavigationBar(
                                containerColor = DarkSurface,
                                contentColor = TextPrimary,
                                tonalElevation = 0.dp
                            ) {
                                NavigationBarItem(
                                    selected = currentScreen is Screen.Home,
                                    onClick = { currentScreen = Screen.Home },
                                    icon = {
                                        Icon(
                                            imageVector = if (currentScreen is Screen.Home) Icons.Filled.Home else Icons.Outlined.Home,
                                            contentDescription = "Home"
                                        )
                                    },
                                    label = { Text("Home", fontSize = 11.sp) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color.Black,
                                        selectedTextColor = AccentPrimary,
                                        indicatorColor = AccentPrimary,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary
                                    )
                                )

                                NavigationBarItem(
                                    selected = currentScreen is Screen.Downloads,
                                    onClick = { currentScreen = Screen.Downloads },
                                    icon = {
                                        Icon(
                                            imageVector = if (currentScreen is Screen.Downloads) Icons.Filled.Download else Icons.Outlined.Download,
                                            contentDescription = "Downloads"
                                        )
                                    },
                                    label = { Text("Downloads", fontSize = 11.sp) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color.Black,
                                        selectedTextColor = AccentPrimary,
                                        indicatorColor = AccentPrimary,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary
                                    )
                                )

                                NavigationBarItem(
                                    selected = currentScreen is Screen.Settings,
                                    onClick = { currentScreen = Screen.Settings },
                                    icon = {
                                        Icon(
                                            imageVector = if (currentScreen is Screen.Settings) Icons.Filled.Settings else Icons.Outlined.Settings,
                                            contentDescription = "Settings"
                                        )
                                    },
                                    label = { Text("Settings", fontSize = 11.sp) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color.Black,
                                        selectedTextColor = AccentPrimary,
                                        indicatorColor = AccentPrimary,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary
                                    )
                                )
                            }
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            when (currentScreen) {
                                is Screen.Home -> {
                                    HomeScreen(
                                        appStorage = appStorage,
                                        downloadManager = downloadManager,
                                        onPlayVideo = { entry ->
                                            currentScreen = Screen.Player(entry)
                                        }
                                    )
                                }
                                is Screen.Downloads -> {
                                    DownloadsScreen(
                                        appStorage = appStorage,
                                        downloadManager = downloadManager,
                                        onPlayDownloaded = { entry ->
                                            currentScreen = Screen.Player(entry)
                                        }
                                    )
                                }
                                is Screen.Settings -> {
                                    SettingsScreen(appStorage = appStorage)
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleIntent(intent: Intent?, onEntryReady: (HistoryEntry) -> Unit) {
        val uri: Uri? = intent?.data
        if (uri != null) {
            val urlString = uri.toString()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val extracted = FacebookExtractor.resolveAndExtract(urlString)
                    val entry = HistoryEntry(
                        id = "hist_${System.currentTimeMillis()}",
                        title = extracted.title,
                        videoPath = extracted.directStreamUrl ?: urlString,
                        originalFbUrl = extracted.originalUrl,
                        fbVideoId = extracted.fbVideoId,
                        mpdContent = extracted.mpdContent
                    )
                    appStorage.saveHistoryEntry(entry)
                    runOnUiThread { onEntryReady(entry) }
                } catch (_: Exception) {}
            }
        }
    }
}
