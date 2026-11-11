package com.cleartether.app.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.cleartether.app.BuildConfig
import com.cleartether.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PlanPeriod { MONTH, YEAR, LIFETIME }

/** One purchasable Premium option, as configured in Play Console (prices are always Play's). */
data class PremiumPlan(
    val period: PlanPeriod,
    /** Recurring price, e.g. "$2.99" (or the one-time price for lifetime). */
    val formattedPrice: String,
    val priceMicros: Long,
    val currencyCode: String,
    /** Free-trial length in days if the user is eligible for a trial offer on this plan. */
    val freeTrialDays: Int? = null,
    internal val details: ProductDetails? = null,
    internal val offerToken: String? = null,
) {
    val id: String get() = period.name.lowercase()
}

/** What the paywall and settings screens need to know about billing. */
data class BillingState(
    val connected: Boolean = false,
    val loaded: Boolean = false,
    val plans: List<PremiumPlan> = emptyList(),
    val ownsPremium: Boolean = false,
    val subscribed: Boolean = false,
    val purchasePending: Boolean = false,
    val message: String? = null,
) {
    fun plan(period: PlanPeriod) = plans.firstOrNull { it.period == period }

    /** Yearly saving versus paying monthly for 12 months, in percent (null if unknown). */
    val yearlySavingsPercent: Int?
        get() {
            val m = plan(PlanPeriod.MONTH) ?: return null
            val y = plan(PlanPeriod.YEAR) ?: return null
            if (m.currencyCode != y.currencyCode || m.priceMicros <= 0) return null
            val pct = Math.round((1.0 - y.priceMicros.toDouble() / (m.priceMicros * 12.0)) * 100).toInt()
            return pct.takeIf { it in 5..95 }
        }

    /** Kept for screens that show a single headline price. */
    val formattedPrice: String? get() = (plan(PlanPeriod.YEAR) ?: plans.firstOrNull())?.formattedPrice
}

/**
 * Google Play Billing for ClearTether Premium.
 *
 * Catalogue (Play Console → Monetize):
 *  - **Subscription** `cleartether_premium` with base plans `monthly` (P1M) and `yearly` (P1Y),
 *    optionally with a free-trial offer. Play only returns offers the user is still eligible for,
 *    so trials show up for new subscribers only.
 *  - **Optional one-time product** `cleartether_premium_lifetime`. It appears in the paywall only
 *    if it exists in Play Console.
 *
 * Play stays the source of truth: purchases are re-queried on every launch, and expired,
 * cancelled, refunded or revoked entitlements drop off. Purchases are signature-verified
 * ([PurchaseVerifier]) and acknowledged (Play auto-refunds unacknowledged purchases after 3
 * days). Entitlement is cached in DataStore, so Premium also works offline and at cold start.
 */
class BillingRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val verifier: PurchaseVerifier = PurchaseVerifier(BuildConfig.PLAY_LICENSE_KEY),
) : PurchasesUpdatedListener {

    private val _state = MutableStateFlow(BillingState())
    val state: StateFlow<BillingState> = _state.asStateFlow()

    /** Effective entitlement: Play purchase, cached entitlement, or the debug-build override. */
    val isPremium: StateFlow<Boolean> = combine(_state, settings.premiumCached, settings.debugPremium) { s, cached, debug ->
        s.ownsPremium || cached || (BuildConfig.DEBUG && debug)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    /** Reads the entitlement without waiting for Play (cached value first). */
    suspend fun currentPremium(): Boolean =
        _state.value.ownsPremium || settings.premiumCached.first() || (BuildConfig.DEBUG && settings.debugPremium.first())

    fun connect() {
        if (client.isReady) {
            scope.launch { refresh() }
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                val ok = result.responseCode == BillingClient.BillingResponseCode.OK
                _state.update { it.copy(connected = ok, message = if (ok) null else it.message) }
                if (ok) scope.launch { refresh() }
            }

            override fun onBillingServiceDisconnected() {
                _state.update { it.copy(connected = false) }
            }
        })
    }

    suspend fun refresh() {
        if (!client.isReady) return
        loadPlans()
        restorePurchases()
    }

    private suspend fun loadPlans() {
        val plans = buildList {
            querySingle(BuildConfig.SUBSCRIPTION_PRODUCT_ID, BillingClient.ProductType.SUBS)?.let { addAll(subscriptionPlans(it)) }
            querySingle(BuildConfig.LIFETIME_PRODUCT_ID, BillingClient.ProductType.INAPP)?.let { details ->
                details.oneTimePurchaseOfferDetails?.let { offer ->
                    add(PremiumPlan(PlanPeriod.LIFETIME, offer.formattedPrice, offer.priceAmountMicros, offer.priceCurrencyCode, details = details))
                }
            }
        }.sortedBy { it.period.ordinal }
        _state.update { it.copy(loaded = true, plans = plans) }
    }

    private suspend fun querySingle(productId: String, type: String): ProductDetails? {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(productId).setProductType(type).build()))
            .build()
        return runCatching { client.queryProductDetails(params).productDetailsList?.firstOrNull() }.getOrNull()
    }

    /** One plan per base plan: the eligible free-trial offer if any, otherwise the base offer. */
    private fun subscriptionPlans(details: ProductDetails): List<PremiumPlan> =
        details.subscriptionOfferDetails.orEmpty()
            .groupBy { it.basePlanId }
            .mapNotNull { (_, offers) ->
                val best = offers.firstOrNull { o -> o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } }
                    ?: offers.firstOrNull { it.offerId == null }
                    ?: offers.first()
                val phases = best.pricingPhases.pricingPhaseList
                val recurring = phases.lastOrNull() ?: return@mapNotNull null
                val period = when (recurring.billingPeriod) {
                    "P1Y", "P12M" -> PlanPeriod.YEAR
                    "P1M", "P4W" -> PlanPeriod.MONTH
                    else -> return@mapNotNull null // other cadences aren't offered in the paywall
                }
                val trial = phases.firstOrNull { it.priceAmountMicros == 0L }?.let { isoPeriodDays(it.billingPeriod) }
                PremiumPlan(
                    period = period,
                    formattedPrice = recurring.formattedPrice,
                    priceMicros = recurring.priceAmountMicros,
                    currencyCode = recurring.priceCurrencyCode,
                    freeTrialDays = trial,
                    details = details,
                    offerToken = best.offerToken,
                )
            }
            .distinctBy { it.period }

    /** Re-reads owned purchases from Play ("Restore purchase"). */
    suspend fun restorePurchases() {
        if (!client.isReady) return
        val subs = runCatching {
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build())
        }.getOrNull()
        val inapp = runCatching {
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build())
        }.getOrNull()
        val ok = BillingClient.BillingResponseCode.OK
        if (subs?.billingResult?.responseCode != ok || inapp?.billingResult?.responseCode != ok) return // keep cache
        val ours = (subs.purchasesList + inapp.purchasesList).filter { isOurs(it) }
        val active = ours.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (active.isEmpty()) {
            _state.update { it.copy(ownsPremium = false, subscribed = false, purchasePending = ours.any { p -> p.purchaseState == Purchase.PurchaseState.PENDING }) }
            settings.setPremiumCached(false) // expired, cancelled, refunded, revoked or never bought
        } else {
            ours.forEach { handlePurchase(it) }
        }
    }

    /** Opens the Play purchase sheet for [plan]. Returns false if the store isn't ready. */
    fun launchPurchase(activity: Activity, plan: PremiumPlan): Boolean {
        val details = plan.details ?: return storeNotReady()
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .apply { plan.offerToken?.let { setOfferToken(it) } }
            .build()
        val result = client.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(productParams)).build(),
        )
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _state.update { it.copy(message = result.debugMessage.ifBlank { "Couldn't open Google Play (${result.responseCode})" }) }
            return false
        }
        return true
    }

    private fun storeNotReady(): Boolean {
        connect()
        _state.update { it.copy(message = "Google Play isn't ready yet. Check your connection and try again.") }
        return false
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach { scope.launch { handlePurchase(it) } }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> scope.launch { restorePurchases() }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> _state.update { it.copy(message = result.debugMessage.ifBlank { "Purchase failed (${result.responseCode})" }) }
        }
    }

    private fun isOurs(p: Purchase) =
        BuildConfig.SUBSCRIPTION_PRODUCT_ID in p.products || BuildConfig.LIFETIME_PRODUCT_ID in p.products

    private suspend fun handlePurchase(purchase: Purchase) {
        if (!isOurs(purchase)) return
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                if (!verifier.verify(purchase.originalJson, purchase.signature)) {
                    Log.w(TAG, "Rejected purchase with invalid signature")
                    _state.update { it.copy(message = "Purchase could not be verified.") }
                    return
                }
                if (!purchase.isAcknowledged) {
                    val ack = client.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build(),
                    )
                    if (ack.responseCode != BillingClient.BillingResponseCode.OK) {
                        Log.w(TAG, "Acknowledge failed: ${ack.debugMessage}") // retried on next launch
                    }
                }
                val isSub = BuildConfig.SUBSCRIPTION_PRODUCT_ID in purchase.products
                _state.update { it.copy(ownsPremium = true, subscribed = it.subscribed || isSub, purchasePending = false, message = null) }
                settings.setPremiumCached(true)
            }
            Purchase.PurchaseState.PENDING -> _state.update { it.copy(purchasePending = true) }
            else -> Unit
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    companion object {
        private const val TAG = "ClearTetherBilling"

        /** Play's subscription center, deep-linked to ClearTether (cancel, switch plan, payment). */
        fun manageSubscriptionUrl(packageName: String) =
            "https://play.google.com/store/account/subscriptions?sku=${BuildConfig.SUBSCRIPTION_PRODUCT_ID}&package=$packageName"

        /** ISO-8601 period ("P3D", "P1W", "P1M") to days. */
        internal fun isoPeriodDays(period: String): Int? {
            val m = Regex("""P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)W)?(?:(\d+)D)?""").matchEntire(period) ?: return null
            val (y, mo, w, d) = m.destructured
            val days = (y.toIntOrNull() ?: 0) * 365 + (mo.toIntOrNull() ?: 0) * 30 + (w.toIntOrNull() ?: 0) * 7 + (d.toIntOrNull() ?: 0)
            return days.takeIf { it > 0 }
        }
    }
}
