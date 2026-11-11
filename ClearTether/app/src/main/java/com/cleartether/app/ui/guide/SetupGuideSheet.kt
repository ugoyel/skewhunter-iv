package com.cleartether.app.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cleartether.app.R
import com.cleartether.app.core.proxy.ClientPlatform
import com.cleartether.app.core.proxy.SetupInstructions
import com.cleartether.app.core.proxy.TetherEndpoint
import com.cleartether.app.ui.theme.MonoStyle

/** Per-OS client setup, generated from the same [SetupInstructions] as the host's web page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupGuideSheet(endpoint: TetherEndpoint, onCopy: (String, String) -> Unit, onDismiss: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val platforms = ClientPlatform.entries
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                stringResource(R.string.guide_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                stringResource(R.string.guide_subtitle, endpoint.baseUrl), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 16.dp) {
                platforms.forEachIndexed { i, p -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(p.displayName) }) }
            }
            val commandLabel = stringResource(R.string.label_command)
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SetupInstructions.steps(platforms[tab], endpoint).forEachIndexed { index, step ->
                    Row {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(26.dp).background(MaterialTheme.colorScheme.primary, CircleShape).padding(top = 3.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(step.text, style = MaterialTheme.typography.bodyMedium)
                            step.command?.let { cmd ->
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)).padding(start = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(cmd, style = MonoStyle, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                                    IconButton(onClick = { onCopy(commandLabel, cmd) }) {
                                        Icon(Icons.Rounded.ContentCopy, stringResource(R.string.cd_copy, commandLabel))
                                    }
                                }
                            }
                            step.linkPath?.let { path ->
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    stringResource(R.string.guide_link, step.linkLabel ?: "", endpoint.baseUrl + path),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
