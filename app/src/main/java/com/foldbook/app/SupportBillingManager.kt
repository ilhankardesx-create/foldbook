package com.foldbook.app

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SupportProduct(
    val productId: String,
    val emoji: String,
    val title: String,
    val priceLabel: String,
    val productDetails: ProductDetails? = null
)

class SupportBillingManager(
    private val activity: Activity
) : PurchasesUpdatedListener {

    companion object {
        const val PRODUCT_SUPPORT_50 = "foldbook_support_50"
        const val PRODUCT_SUPPORT_100 = "foldbook_support_100"
        const val PRODUCT_SUPPORT_200 = "foldbook_support_200"

        private val baseProducts = listOf(
            SupportProduct(
                productId = PRODUCT_SUPPORT_50,
                emoji = "☕",
                title = "Küçük Destek",
                priceLabel = "50 TL"
            ),
            SupportProduct(
                productId = PRODUCT_SUPPORT_100,
                emoji = "❤️",
                title = "Destek",
                priceLabel = "100 TL"
            ),
            SupportProduct(
                productId = PRODUCT_SUPPORT_200,
                emoji = "🚀",
                title = "Büyük Destek",
                priceLabel = "200 TL"
            )
        )
    }

    private val _products = MutableStateFlow(baseProducts)
    val products: StateFlow<List<SupportProduct>> = _products.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val billingClient = BillingClient.newBuilder(activity)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        if (billingClient.isReady) {
            _ready.value = true
            queryProducts()
            queryExistingPurchases()
            return
        }

        billingClient.startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        _ready.value = true
                        queryProducts()
                        queryExistingPurchases()
                    } else {
                        _ready.value = false
                        _message.value = "Google Play ödeme servisine bağlanılamadı."
                    }
                }

                override fun onBillingServiceDisconnected() {
                    _ready.value = false
                }
            }
        )
    }

    fun close() {
        billingClient.endConnection()
    }

    fun clearMessage() {
        _message.value = null
    }

    fun purchase(productId: String) {
        if (!billingClient.isReady) {
            _message.value = "Google Play ödeme servisi henüz hazır değil."
            return
        }

        val supportProduct = _products.value.firstOrNull { it.productId == productId }
        val details = supportProduct?.productDetails

        if (details == null) {
            _message.value =
                "Bu destek seçeneği Google Play Console'da etkinleştirildiğinde ödeme açılacak."
            return
        }

        val offer = details.oneTimePurchaseOfferDetailsList?.firstOrNull()
        if (offer == null) {
            _message.value = "Bu destek seçeneği şu anda satın alınamıyor."
            return
        }

        val productParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)

        offer.offerToken?.takeIf { it.isNotBlank() }?.let { token ->
            productParamsBuilder.setOfferToken(token)
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParamsBuilder.build()))
            .build()

        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _message.value = "Google Play ödeme ekranı açılamadı."
        }
    }

    private fun queryProducts() {
        val queryProducts = baseProducts.map { item ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(item.productId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(queryProducts)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, queryResult ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                return@queryProductDetailsAsync
            }

            val detailsById = queryResult.productDetailsList.associateBy { it.productId }

            _products.value = baseProducts.map { item ->
                val details = detailsById[item.productId]
                val playPrice = details
                    ?.oneTimePurchaseOfferDetailsList
                    ?.firstOrNull()
                    ?.formattedPrice

                item.copy(
                    priceLabel = playPrice ?: item.priceLabel,
                    productDetails = details
                )
            }
        }
    }

    private fun queryExistingPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.forEach(::processPurchase)
            }
        }
    }

    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: List<Purchase>?
    ) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases.orEmpty().forEach(::processPurchase)
            }

            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _message.value = "Ödeme iptal edildi."
            }

            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                queryExistingPurchases()
            }

            else -> {
                _message.value = "Ödeme tamamlanamadı. Lütfen tekrar dene."
            }
        }
    }

    private fun processPurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            _message.value = "Ödeme beklemede. Google Play onayladığında tamamlanacak."
            return
        }

        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            return
        }

        val isSupportProduct = purchase.products.any { purchasedId ->
            baseProducts.any { it.productId == purchasedId }
        }
        if (!isSupportProduct) return

        val consumeParams = ConsumeParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        billingClient.consumeAsync(consumeParams) { result, _ ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                _message.value = "Desteğin için çok teşekkürler ❤️"
                queryProducts()
            } else {
                _message.value =
                    "Ödeme alındı. Google Play işlemi tamamlamaya devam ediyor."
            }
        }
    }
}
