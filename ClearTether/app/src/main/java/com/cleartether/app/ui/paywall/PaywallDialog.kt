package com.cleartether.app.ui.paywall

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.HourglassDisabled
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cleartether.app.R
import com.cleartether.app.billing.BillingState
import com.cleartether.app.core.session.FreeTierPolicy
import com.cleartether.app.ui.PaywallReason
import com.cleartether.app.ui.theme.Brand
import com.cleartether.app.util.Format

/** Upgrade overlay shown when the free session ends, while cooling down, or on demand. */
@Composable
fun PaywallDialog(
    reason: PaywallReason,
    billing: BillingState,
    freeTier: FreeTierPolicy,
    cooldownRemainingMs: Long?,
    onPurchase: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(20.dp).widthIn(max = 460.dp).fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().background(Brand.headerGradient).padding(24.dp)) {
                    IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.action_close), tint = Color.White)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Box(
                            Modifier.size(72.dp).background(Brand.premiumGradient, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.WorkspacePremium, null, tint = Color(0xFF2C1A00), modifier = Modifier.size(40.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            stringResource(R.string.paywall_title), style = MaterialTheme.typography.headlineMedium,
                            color = Color.White, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            reasonText(reason, freeTier, cooldownRemainingMs),
                            style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center,
                        )
                    }
                }
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Benefit(Icons.Rounded.AllInclusive, R.string.benefit_unlimited_title, R.string.benefit_unlimited_body)
                    Benefit(Icons.Rounded.HourglassDisabled, R.string.benefit_no_wait_title, R.string.benefit_no_wait_body)
                    Benefit(Icons.Rounded.Devices, R.string.benefit_devices_title, R.string.benefit_devices_body)
                    Benefit(Icons.Rounded.Favorite, R.string.benefit_support_title, R.string.benefit_support_body)
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = onPurchase,
                        enabled = !billing.purchasePending,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Brand.Gold, contentColor = Color(0xFF2C1A00)),
                    ) {
                        if (billing.purchasePending) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF2C1A00))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.paywall_pending))
                        } else {
                            Text(
                                billing.formattedPrice?.let { stringResource(R.string.paywall_cta_price, it) } ?: stringResource(R.string.paywall_cta),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.paywall_fine_print),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                    billing.message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = onRestore) { Text(stringResource(R.string.action_restore)) }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_not_now)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun reasonText(reason: PaywallReason, freeTier: FreeTierPolicy, cooldownRemainingMs: Long?): String = when (reason) {
    PaywallReason.TIME_LIMIT -> stringResource(R.string.paywall_reason_time, freeTier.sessionTime.inWholeMinutes)
    PaywallReason.DATA_LIMIT -> stringResource(R.string.paywall_reason_data, Format.bytes(freeTier.sessionBytes))
    PaywallReason.COOLDOWN -> stringResource(R.string.paywall_reason_cooldown, Format.duration(cooldownRemainingMs ?: 0))
    PaywallReason.UPGRADE -> stringResource(R.string.paywall_reason_upgrade)
}

@Composable
private fun Benefit(icon: ImageVector, title: Int, body: Int) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(icon, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
    }
}
