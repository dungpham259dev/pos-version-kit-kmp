package com.vtcode.pos.version.presentation.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.vtcode.pos.version.domain.entity.compareVersions
import com.vtcode.pos.version.presentation.util.FormatUtils

@Composable
fun Modifier.VersionListItem(
    state: VersionListItemState,
    isAnyDownloading: Boolean = false,
    isInstalling: Boolean = false,
    currentAppVersion: String = "",
    onDownload: () -> Unit = {},
    onInstall: () -> Unit = {}
) {
    val colors = LocalVersionListItemColors.current

    val effectiveState = when {
        isAnyDownloading && state !is VersionListItemState.Downloading &&
                state !is VersionListItemState.Current -> {
            VersionListItemState.Disabled(
                version = state.version,
                channel = state.channel,
                releaseDate = state.releaseDate,
                releaseNote = state.releaseNote,
                fileSizeBytes = state.fileSizeBytes,
                fileId = state.fileId,
                reason = "Đang tải phiên bản khác"
            )
        }

        state is VersionListItemState.Available && isDowngrade(
            currentAppVersion,
            state.version
        ) -> {
            VersionListItemState.Disabled(
                version = state.version,
                channel = state.channel,
                releaseDate = state.releaseDate,
                releaseNote = state.releaseNote,
                fileSizeBytes = state.fileSizeBytes,
                fileId = state.fileId,
                reason = "Không thể cài phiên bản cũ"
            )
        }

        else -> state
    }

    Surface(
        modifier = fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = when (state) {
            is VersionListItemState.Current -> Color.White.copy(alpha = 0.7f)
            is VersionListItemState.Disabled -> Color.White.copy(alpha = 0.5f)
            else -> Color.White
        },
        tonalElevation = 2.dp,
        shadowElevation = when (state) {
            is VersionListItemState.Downloaded -> 4.dp
            else -> 2.dp
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // Main Row: Version + Size + Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VersionInfo(state = effectiveState, colors = colors)

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = FormatUtils.formatFileSize(effectiveState.fileSizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp)
                )

                ActionButton(
                    state = effectiveState,
                    colors = colors,
                    isInstalling = isInstalling,
                    onDownload = onDownload,
                    onInstall = onInstall
                )
            }

            // Release note - prefix normal, content bold
            if (effectiveState.releaseNote.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = buildAnnotatedString {
                        append("Thông tin cập nhật:")
                        append(" ")
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(effectiveState.releaseNote)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.9f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun VersionInfo(
    state: VersionListItemState,
    colors: VersionListItemColors
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state is VersionListItemState.Current) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(colors.currentIndicator, shape = CircleShape)
                )
            }

            Text(
                text = "v${state.version}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )

            ChannelBadge(channel = state.channel)
        }

        // Release date (if available) - API returns dd/MM/yyyy format
        if (state.releaseDate.isNotBlank()) {
            Text(
                text = state.releaseDate,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChannelBadge(channel: String) {
    val (label, color) = when (channel.lowercase()) {
        "stable", "ổn-định" -> "ỔN ĐỊNH" to MaterialTheme.colorScheme.primary
        "beta", "thử-nghiệm" -> "THỬ NGHIỆM" to MaterialTheme.colorScheme.tertiary
        "alpha" -> "ALPHA" to MaterialTheme.colorScheme.error
        "legacy", "cũ" -> "CŨ" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "ỔN ĐỊNH" to MaterialTheme.colorScheme.primary
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun ActionButton(
    state: VersionListItemState,
    colors: VersionListItemColors,
    isInstalling: Boolean = false,
    onDownload: () -> Unit,
    onInstall: () -> Unit
) {
    when (state) {
        is VersionListItemState.Available -> {
            Button(
                onClick = onDownload,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(36.dp),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Tải",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        is VersionListItemState.Downloading -> {
            Button(
                onClick = {},
                enabled = false,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary.copy(alpha = 0.7f),
                    contentColor = colors.onPrimary,
                    disabledContainerColor = colors.primary.copy(alpha = 0.7f),
                    disabledContentColor = colors.onPrimary
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(36.dp),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = colors.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Đang tải…",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        is VersionListItemState.Downloaded -> {
            Button(
                onClick = onInstall,
                enabled = !isInstalling,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                    disabledContainerColor = colors.primary.copy(alpha = 0.7f),
                    disabledContentColor = colors.onPrimary
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(36.dp),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding
            ) {
                if (isInstalling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = colors.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.InstallMobile,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "Cài",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        is VersionListItemState.Current -> {
            OutlinedButton(
                onClick = onInstall,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(36.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = colors.onSurfaceVariant
                )
            ) {
                Text(
                    "Cài lại",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        is VersionListItemState.Disabled -> {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = colors.disabled,
                modifier = Modifier.height(36.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = "───",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

// Helper
private fun isDowngrade(currentAppVersion: String, itemVersion: String): Boolean {
    return compareVersions(itemVersion, currentAppVersion) < 0
}
