package com.foodtracker.android.recipe.paid

import android.content.Context
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
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.foodtracker.recipe.RECIPE_PACKS
import com.foodtracker.recipe.RecipeStore
import com.foodtracker.recipe.StorePack
import com.foodtracker.recipe.StorePurchase
import com.foodtracker.recipe.StoreUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Play Billing for the three consumable packs. It only reads prices, opens Play's payment sheet
 * and lists what Play still holds: the purchase token goes to the server, which checks it with
 * Google and consumes it. This class never consumes or acknowledges anything; an unconsumed
 * purchase keeps showing up in [purchases] until the server has, which is what makes resending
 * it at launch and on resume work.
 */
class PlayRecipeStore(context: Context, private val firebase: RecipeFirebase) : RecipeStore {

    private val appContext = context.applicationContext
    private val _updates = MutableSharedFlow<StoreUpdate>(extraBufferCapacity = 16)
    override val updates: Flow<StoreUpdate> = _updates.asSharedFlow()
    override val isSupported: Boolean = true

    private val listener = PurchasesUpdatedListener { result, purchases ->
        val update = when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> StoreUpdate.Purchases(purchases.orEmpty().mapNotNull { it.toStorePurchase() })
            BillingClient.BillingResponseCode.USER_CANCELED -> StoreUpdate.Cancelled
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> StoreUpdate.AlreadyOwned
            else -> StoreUpdate.Unavailable
        }
        _updates.tryEmit(update)
    }

    // Created on first use: someone who never opens the packs never binds to Play's service.
    private val client: BillingClient by lazy {
        BillingClient.newBuilder(appContext)
            .setListener(listener)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
    }
    private val connecting = Mutex()

    private suspend fun ready(): Boolean = connecting.withLock {
        if (client.isReady) return@withLock true
        suspendCancellableCoroutine { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (continuation.isActive) continuation.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (continuation.isActive) continuation.resume(false)
                }
            })
        }
    }

    private suspend fun details(productIds: Collection<String>): List<ProductDetails>? {
        if (!ready()) return null
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                }
            )
            .build()
        val result = client.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return null
        return result.productDetailsList.orEmpty()
    }

    override suspend fun packs(): List<StorePack>? = guarded(null) {
        details(RECIPE_PACKS.keys)?.mapNotNull { product ->
            val offer = product.offer() ?: return@mapNotNull null
            StorePack(
                productId = product.productId,
                recipes = RECIPE_PACKS[product.productId] ?: return@mapNotNull null,
                price = offer.formattedPrice,
                priceMicros = offer.priceAmountMicros,
                currencyCode = offer.priceCurrencyCode
            )
        }
    }

    override suspend fun buy(productId: String): Boolean = guarded(false) {
        val activity = CurrentActivity.activity ?: return@guarded false
        // Fetched again rather than kept: stale details can make launchBillingFlow fail.
        val product = details(listOf(productId))?.firstOrNull() ?: return@guarded false
        val offer = product.offer() ?: return@guarded false
        // Ties the purchase to its buyer for the server (functions/CONTRACT.md). The user exists
        // by now: the purchase flow creates or signs one in before opening Play's sheet.
        val buyer = firebase.auth().currentUser?.uid?.let(::purchaseAccountId)
        val params = BillingFlowParams.newBuilder()
            .apply { buyer?.let(::setObfuscatedAccountId) }
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .apply { offer.offerToken?.let { setOfferToken(it) } }
                        .build()
                )
            )
            .build()
        val result = withContext(Dispatchers.Main) { client.launchBillingFlow(activity, params) }
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> true
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                _updates.tryEmit(StoreUpdate.AlreadyOwned)
                true
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _updates.tryEmit(StoreUpdate.Cancelled)
                true
            }
            else -> false
        }
    }

    override suspend fun purchases(): List<StorePurchase>? = guarded(null) {
        if (!ready()) return@guarded null
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        val result = client.queryPurchasesAsync(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return@guarded null
        result.purchasesList.mapNotNull { it.toStorePurchase() }
    }

    private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }
}

/** One-time products have a list of purchase options since Billing 8; the first is the base. */
private fun ProductDetails.offer(): ProductDetails.OneTimePurchaseOfferDetails? =
    oneTimePurchaseOfferDetailsList?.firstOrNull() ?: oneTimePurchaseOfferDetails

private fun Purchase.toStorePurchase(): StorePurchase? {
    val ours = products.filter { it in RECIPE_PACKS }
    if (ours.isEmpty()) return null
    return when (purchaseState) {
        Purchase.PurchaseState.PURCHASED -> StorePurchase(purchaseToken, ours, quantity, pending = false)
        Purchase.PurchaseState.PENDING -> StorePurchase(purchaseToken, ours, quantity, pending = true)
        else -> null
    }
}
