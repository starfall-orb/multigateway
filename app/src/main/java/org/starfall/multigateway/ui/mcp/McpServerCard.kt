package org.starfall.multigateway.ui.mcp

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.starfall.multigateway.R
import org.starfall.multigateway.data.model.McpInfo
import org.starfall.multigateway.ui.components.EntityIcon
import org.starfall.multigateway.ui.components.ItemOverflowMenu
import org.starfall.multigateway.ui.components.MorphingCardLayout

@Composable
fun McpUnifiedCard(server: McpInfo, isGrid: Boolean, modifier: Modifier = Modifier, toolCount: Int?, toolError: String?,
    loading: Boolean, onToolErrorClick: (String) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val corner by animateDpAsState(if (isGrid) 20.dp else 16.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow), label = "mcpShapeCorner")
    Surface(shape = RoundedCornerShape(corner), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant), modifier = modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        MorphingCardLayout(isGrid = isGrid, modifier = Modifier.fillMaxWidth().padding(14.dp),
            icon = { EntityIcon(server.icon, Modifier.size(42.dp), fallback = Icons.Outlined.Extension, matchName = server.name) },
            actions = { ItemOverflowMenu(onEdit = onEdit, onDelete = onDelete, deleteColor = MaterialTheme.colorScheme.error) },
            content = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(server.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.mcp_protocol, mcpTransportLabel(server.protocol)),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.primary)
                    Text(server.url ?: stringResource(R.string.mcp_no_endpoint), style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    when {
                        toolError != null -> Row(Modifier.clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)).clickable { onToolErrorClick(toolError) }
                            .padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.mcp_error_details), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.error)
                        }
                        loading -> Text(stringResource(R.string.loading_tools), style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.primary)
                        toolCount != null -> Text(stringResource(R.string.cached_tools_count, toolCount),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.outline)
                    }
                }
            })
    }
}
