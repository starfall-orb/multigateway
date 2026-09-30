package org.starfall.multigateway.ui.drawer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MenuView(
    onNavigateToProviders: () -> Unit,
    onNavigateToMcp: () -> Unit,
    onNavigateToSpeech: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSystemTools: () -> Unit,
    onNavigateToStorage: () -> Unit,
    onCloseMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: Close icon + "Menu" title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCloseMenu) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Menu",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                thickness = 0.5.dp
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Default Models (Top)
                item {
                    MenuItemTile(
                        icon = Icons.Outlined.Build,
                        title = "Default Models",
                        subtitle = "Image and video generation",
                        onClick = onNavigateToSystemTools
                    )
                }

                // 2. Providers (Second)
                item {
                    MenuItemTile(
                        icon = Icons.Outlined.CloudQueue,
                        title = "Providers",
                        subtitle = "Configure API keys & endpoints",
                        onClick = onNavigateToProviders
                    )
                }

                // 4. MCP Manage
                item {
                    MenuItemTile(
                        icon = Icons.Outlined.Extension,
                        title = "MCP Manage",
                        subtitle = "Model Context Protocol servers",
                        onClick = onNavigateToMcp
                    )
                }

                // 5. Speech Services
                item {
                    MenuItemTile(
                        icon = Icons.Outlined.RecordVoiceOver,
                        title = "Speech Services",
                        subtitle = "Text-to-speech configuration",
                        onClick = onNavigateToSpeech
                    )
                }

                // 6. General Settings
                item {
                    MenuItemTile(
                        icon = Icons.Outlined.Settings,
                        title = "General Settings",
                        subtitle = "Appearance, preferences & data",
                        onClick = onNavigateToSettings
                    )
                }

                // 7. Storage (At the bottom / last)
                item {
                    MenuItemTile(
                        icon = Icons.Outlined.Storage,
                        title = "Storage",
                        subtitle = "Tool files, images and videos",
                        onClick = onNavigateToStorage
                    )
                }
            }
        }
    }
}

@Composable
fun MenuItemTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                modifier = Modifier.size(12.dp)
            )
        }
    }
}
