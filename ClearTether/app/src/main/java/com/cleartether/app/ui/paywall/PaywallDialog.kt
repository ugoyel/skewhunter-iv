package com.cleartether.app.ui.paywall

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cleartether.app.R
import com.cleartether.app.billing.BillingState
import com.cleartether.app.billing.PlanPeriod
import com.cleartether.app.billing.PremiumPlan
import com.cleartether.app.core.session.FreeTierPolicy
import com.cleartether.app.ui.PaywallReason
import com.cleartether.app.ui.theme.Brand
import com.cleartether.app.util.Format
import java.text.NumberFormat
import java.util.Currency

/**
 * Upgrade overlay: shown when the free daily minutes run out, when they're already used up at
 * start, or on demand. Lists the plans Play returned (yearly first, as the recommended plan),
 * with the price, period, trial and auto-renewal terms Google Play policy requires.
 */
@Composable
fun PaywallDialog(
    reason: PaywallReason,
    billing: BillingState,
    freeTier: FreeTierPolicy,
    cooldownRemainingMs: Long?,
    onPurchase: (PremiumPlan) -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val plans = billing.plans.sortedBy { order(it.period) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = plans.firstOrNull { it.id == selectedId } ?: plans.firstOrNull()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(16.dp).widthIn(max = 460.dp).fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Header(reasonText(reason, freeTier, cooldownRemainingMs), onDismiss)
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Benefit(Icons.Rounded.AllInclusive, R.string.benefit_unlimited_title, R.string.benefit_unlimited_body)
                    Benefit(Icons.Rounded.Link, R.string.benefit_no_wait_title, R.string.benefit_no_wait_body)
                    Benefit(Icons.Rounded.Devices, R.string.benefit_devices_title, R.string.benefit_devices_body)
                    Spacer(Modifier.height(4.dp))

                    when {
                        plans.isNotEmpty() -> plans.forEach { plan ->
                            PlanCard(
                                plan = plan,
                                selected = plan == selected,
                                savingsPercent = if (plan.period == PlanPeriod.YEAR) billing.yearlySavingsPercent else null,
                                onSelect = { selectedId = plan.id },
                            )
                        }
                        !billing.loaded -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.paywall_loading), style = MaterialTheme.typography.bodyMedium)
                        }
                        else -> Text(
                            stringResource(R.string.paywall_unavailable),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Button(
                        onClick = { selected?.let(onPurchase) },
                        enabled = selected != null && !billing.purchasePending,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Brand.Gold, contentColor = Color(0xFF2C1A00)),
                    ) {
                        if (billing.purchasePending) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF2C1A00))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.paywall_pending))
                        } else {
                            Text(ctaText(selected), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    selected?.let {
                        Text(
                            termsText(it), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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

private fun order(period: PlanPeriod) = when (period) {
    PlanPeriod.YEAR -> 0
    PlanPeriod.MONTH -> 1
    PlanPeriod.LIFETIME -> 2
}

@Composable
private fun Header(subtitle: String, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Brand.headerGradient).padding(22.dp)) {
        IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)) {
            Icon(Icons.Rounded.Close, stringResource(R.string.action_close), tint = Color.White)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.size(64.dp).background(Brand.premiumGradient, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.WorkspacePremium, null, tint = Color(0xFF2C1A00), modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.paywall_title), style = MaterialTheme.typography.headlineMedium, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PlanCard(plan: PremiumPlan, selected: Boolean, savingsPercent: Int?, onSelect: () -> Unit) {
    val border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    Row(
        Modifier
            .fillMaxWidth()
            .border(border, RoundedCornerShape(18.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent,
                RoundedCornerShape(18.dp),
            )
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(planName(plan.period)), style = MaterialTheme.typography.titleMedium)
                if (savingsPercent != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.plan_badge_save, savingsPercent),
                        style = MaterialTheme.typography.labelMedium, color = Color(0xFF2C1A00),
                        modifier = Modifier.background(Brand.Gold, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            val sub = when {
                plan.freeTrialDays != null -> pluralStringResource(R.plurals.plan_trial, plan.freeTrialDays, plan.freeTrialDays)
                plan.period == PlanPeriod.YEAR -> monthlyEquivalent(plan)?.let { stringResource(R.string.plan_yearly_equiv, it) }
                plan.period == PlanPeriod.LIFETIME -> stringResource(R.string.plan_lifetime_hint)
                else -> stringResource(R.string.plan_monthly_hint)
            }
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(priceLabel(plan), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 8.dp))
    }
}

private fun planName(period: PlanPeriod) = when (period) {
    PlanPeriod.YEAR -> R.string.plan_yearly
    PlanPeriod.MONTH -> R.string.plan_monthly
    PlanPeriod.LIFETIME -> R.string.plan_lifetime
}

@Composable
private fun priceLabel(plan: PremiumPlan) = when (plan.period) {
    PlanPeriod.YEAR -> stringResource(R.string.plan_per_year, plan.formattedPrice)
    PlanPeriod.MONTH -> stringResource(R.string.plan_per_month, plan.formattedPrice)
    PlanPeriod.LIFETIME -> plan.formattedPrice
}

private fun monthlyEquivalent(plan: PremiumPlan): String? = runCatching {
    NumberFormat.getCurrencyInstance().apply { currency = Currency.getInstance(plan.currencyCode) }
        .format(plan.priceMicros / 1_000_000.0 / 12)
}.getOrNull()

@Composable
private fun ctaText(plan: PremiumPlan?): String = when {
    plan == null -> stringResource(R.string.paywall_cta_generic)
    plan.freeTrialDays != null -> pluralStringResource(R.plurals.paywall_cta_trial, plan.freeTrialDays, plan.freeTrialDays)
    plan.period == PlanPeriod.LIFETIME -> stringResource(R.string.paywall_cta_lifetime, plan.formattedPrice)
    else -> stringResource(R.string.paywall_cta_subscribe, priceLabel(plan))
}

@Composable
private fun termsText(plan: PremiumPlan): String = when {
    plan.period == PlanPeriod.LIFETIME -> stringResource(R.string.paywall_terms_lifetime)
    plan.freeTrialDays != null -> pluralStringResource(R.plurals.paywall_terms_trial, plan.freeTrialDays, plan.freeTrialDays, priceLabel(plan))
    else -> stringResource(R.string.paywall_terms_sub, priceLabel(plan))
}

@Composable
private fun reasonText(reason: PaywallReason, freeTier: FreeTierPolicy, cooldownRemainingMs: Long?): String = when (reason) {
    PaywallReason.TIME_LIMIT -> freeTier.dailyTime.inWholeMinutes.toInt().let { pluralStringResource(R.plurals.paywall_reason_time, it, it) }
    PaywallReason.DATA_LIMIT -> stringResource(R.string.paywall_reason_data, Format.bytes(freeTier.bytes))
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
