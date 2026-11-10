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

/** What the paywall and settings screens need to know about billing. */
data class BillingState(
    val connected: Boolean = false,
    val productLoaded: Boolean = false,
    val formattedPrice: String? = null,
    val ownsPremium: Boolean = false,
    val purchasePending: Boolean = false,
    val message: String? = null,
)

/**
 * Google Play Billing integration for the "ClearTether Premium, lifetime" one-time product.
 *
 * - Play is the source of truth: purchases are re-queried on every launch. A refunded or
 *   revoked purchase disappears from `queryPurchasesAsync`, and premium is withdrawn.
 * - Entitlement is cached in DataStore, so premium also works offline and right at cold start.
 * - Purchases are signature-verified ([PurchaseVerifier]) and acknowledged within the 3-day
 *   window (otherwise Play refunds them automatically).
 * - Pending purchases (cash or bank payments) unlock once they complete.
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

    private var productDetails: ProductDetails? = null

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
                _state.update { it.copy(connected = ok, message = if (ok) null else result.debugMessage) }
                if (ok) scope.launch { refresh() }
            }

            override fun onBillingServiceDisconnected() {
                _state.update { it.copy(connected = false) }
            }
        })
    }

    suspend fun refresh() {
        if (!client.isReady) return
        loadProduct()
        restorePurchases()
    }

    private suspend fun loadProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(BuildConfig.PREMIUM_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            ).build()
        val result = client.queryProductDetails(params)
        val details = result.productDetailsList?.firstOrNull()
        productDetails = details
        _state.update {
            it.copy(productLoaded = details != null, formattedPrice = details?.oneTimePurchaseOfferDetails?.formattedPrice)
        }
    }

    /** Re-reads owned purchases from Play ("Restore purchase"). */
    suspend fun restorePurchases() {
        if (!client.isReady) return
        val result = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        )
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return
        val ours = result.purchasesList.filter { BuildConfig.PREMIUM_PRODUCT_ID in it.products }
        if (ours.isEmpty()) {
            _state.update { it.copy(ownsPremium = false, purchasePending = false) }
            settings.setPremiumCached(false) // refunded, revoked or never bought
        } else {
            ours.forEach { handlePurchase(it) }
        }
    }

    /** Opens the Play purchase sheet. Returns false if the product isn't available yet. */
    fun launchPurchase(activity: Activity): Boolean {
        val details = productDetails ?: run {
            connect()
            _state.update { it.copy(message = "Store not ready yet. Please try again in a moment.") }
            return false
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()
        val result = client.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(productParams)).build(),
        )
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach { scope.launch { handlePurchase(it) } }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> scope.launch { restorePurchases() }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> _state.update { it.copy(message = result.debugMessage.ifBlank { "Purchase failed (${result.responseCode})" }) }
        }
    }

    private suspend fun handlePurchase(purchase: Purchase) {
        if (BuildConfig.PREMIUM_PRODUCT_ID !in purchase.products) return
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
                _state.update { it.copy(ownsPremium = true, purchasePending = false, message = null) }
                settings.setPremiumCached(true)
            }
            Purchase.PurchaseState.PENDING -> _state.update { it.copy(purchasePending = true) }
            else -> Unit
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private companion object {
        const val TAG = "ClearTetherBilling"
    }
}
