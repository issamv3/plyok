package com.ply.mpd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ply.mpd.data.models.DownloadItem
import com.ply.mpd.data.models.DownloadStatus
import com.ply.mpd.data.models.HistoryEntry
import com.ply.mpd.data.storage.AppStorage
import com.ply.mpd.network.DownloadManager
import com.ply.mpd.ui.theme.AccentPrimary
import com.ply.mpd.ui.theme.DarkBackground
import com.ply.mpd.ui.theme.DarkBorder
import com.ply.mpd.ui.theme.DarkSurface
import com.ply.mpd.ui.theme.DarkSurfaceVariant
import com.ply.mpd.ui.theme.QualityHdColor
import com.ply.mpd.ui.theme.QualitySdColor
import com.ply.mpd.ui.theme.TextPrimary
import com.ply.mpd.ui.theme.TextSecondary
import com.ply.mpd.ui.theme.TextTertiary

@Composable
fun DownloadsScreen(
    appStorage: AppStorage,
    downloadManager: DownloadManager,
    onPlayDownloaded: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val downloads by appStorage.downloads.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    val activeDownloads = downloads.filter { it.status != DownloadStatus.COMPLETED }
    val completedDownloads = downloads.filter { it.status == DownloadStatus.COMPLETED }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Downloads",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        // Tabs
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = DarkSurface,
            contentColor = AccentPrimary,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = AccentPrimary
                )
            }
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = {
                    Text(
                        text = "Active (${activeDownloads.size})",
                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp
                    )
                }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = {
                    Text(
                        text = "Completed (${completedDownloads.size})",
                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp
                    )
                }
            )
        }

        // List Content
        val currentList = if (selectedTab == 0) activeDownloads else completedDownloads

        if (currentList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (selectedTab == 0) Icons.Default.Download else Icons.Default.DownloadDone,
                        contentDescription = "Empty",
                        tint = TextTertiary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (selectedTab == 0) "No active downloads" else "No downloaded videos yet",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(currentList, key = { it.id }) { item ->
                    DownloadCard(
                        item = item,
                        onPause = { downloadManager.pauseDownload(item.id) },
                        onResume = { downloadManager.resumeDownload(item.id) },
                        onCancel = { downloadManager.cancelDownload(item.id) },
                        onPlay = {
                            val entry = HistoryEntry(
                                id = item.id,
                                title = item.title,
                                videoPath = item.targetFilePath,
                                originalFbUrl = item.originalFbUrl,
                                fbVideoId = item.fbVideoId,
                                isDownloaded = true
                            )
                            onPlayDownloaded(entry)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DownloadCard(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onPlay: () -> Unit
) {
    val isCompleted = item.status == DownloadStatus.COMPLETED
    val isHd = item.qualityBadge == "HD"
    val badgeColor = if (isHd) QualityHdColor else QualitySdColor

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isCompleted) { onPlay() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(DarkBorder, DarkBorder)))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(badgeColor.copy(alpha = 0.2f))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = item.qualityBadge,
                            color = badgeColor,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = buildString {
                                if (item.resolution.isNotBlank()) append("${item.resolution} • ")
                                if (item.totalBytes > 0) {
                                    val totalMb = item.totalBytes / (1024 * 1024.0)
                                    val downloadedMb = item.bytesDownloaded / (1024 * 1024.0)
                                    append(String.format("%.1f MB / %.1f MB", downloadedMb, totalMb))
                                } else if (item.bytesDownloaded > 0) {
                                    val downloadedMb = item.bytesDownloaded / (1024 * 1024.0)
                                    append(String.format("%.1f MB", downloadedMb))
                                }
                            },
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }

                // Action controls
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (item.status) {
                        DownloadStatus.DOWNLOADING -> {
                            IconButton(onClick = onPause, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Pause, contentDescription = "Pause", tint = AccentPrimary)
                            }
                            IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = TextSecondary)
                            }
                        }
                        DownloadStatus.PAUSED -> {
                            IconButton(onClick = onResume, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = AccentPrimary)
                            }
                            IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = TextSecondary)
                            }
                        }
                        DownloadStatus.FAILED -> {
                            IconButton(onClick = onResume, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = Color(0xFFEF4444))
                            }
                            IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = TextSecondary)
                            }
                        }
                        DownloadStatus.COMPLETED -> {
                            IconButton(onClick = onPlay, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = QualityHdColor)
                            }
                            IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = TextSecondary)
                            }
                        }
                        else -> {}
                    }
                }
            }

            // Progress bar
            if (!isCompleted) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { (item.progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = if (item.status == DownloadStatus.FAILED) Color(0xFFEF4444) else AccentPrimary,
                    trackColor = DarkSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = when (item.status) {
                            DownloadStatus.DOWNLOADING -> "Downloading..."
                            DownloadStatus.PAUSED -> "Paused"
                            DownloadStatus.FAILED -> item.errorMessage ?: "Failed"
                            DownloadStatus.QUEUED -> "Queued..."
                            else -> ""
                        },
                        fontSize = 11.sp,
                        color = if (item.status == DownloadStatus.FAILED) Color(0xFFEF4444) else TextSecondary
                    )
                    Text(
                        text = "${item.progress}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AccentPrimary
                    )
                }
            }
        }
    }
}
