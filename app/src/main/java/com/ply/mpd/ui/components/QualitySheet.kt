package com.ply.mpd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ply.mpd.data.models.DashRepresentation
import com.ply.mpd.ui.theme.DarkBorder
import com.ply.mpd.ui.theme.DarkSurface
import com.ply.mpd.ui.theme.DarkSurfaceVariant
import com.ply.mpd.ui.theme.QualityHdColor
import com.ply.mpd.ui.theme.QualitySdColor
import com.ply.mpd.ui.theme.TextPrimary
import com.ply.mpd.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySheet(
    title: String,
    representations: List<DashRepresentation>,
    onSelectQuality: (DashRepresentation) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val videoTracks = representations.filter { it.isVideo }
    val displayTracks = if (videoTracks.isNotEmpty()) videoTracks else representations

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(DarkBorder)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Select Download Quality",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                fontSize = 13.sp,
                color = TextSecondary,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (displayTracks.isEmpty()) {
                Text(
                    text = "No quality tracks found. Click to download default stream.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    items(displayTracks, key = { it.id }) { rep ->
                        QualityItemCard(
                            representation = rep,
                            onClick = {
                                onSelectQuality(rep)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QualityItemCard(
    representation: DashRepresentation,
    onClick: () -> Unit
) {
    val trackHeight = representation.height ?: 0
    val isHd = trackHeight >= 720
    val badgeText = if (isHd) "HD" else "SD"
    val badgeColor = if (isHd) QualityHdColor else QualitySdColor

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    val labelText = if (trackHeight > 0) "${trackHeight}p" else representation.label.ifEmpty { "Stream ${representation.id}" }
                    Text(
                        text = labelText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val desc = buildString {
                        if (representation.width != null && representation.height != null) {
                            append("${representation.width}x${representation.height}")
                        }
                        if (representation.bandwidth > 0) {
                            if (isNotEmpty()) append(" • ")
                            append("${representation.bandwidth / 1000} kbps")
                        }
                    }
                    Text(
                        text = desc,
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            Button(
                onClick = onClick,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = badgeColor.copy(alpha = 0.15f),
                    contentColor = badgeColor
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Download Quality",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Get", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
    }
}
