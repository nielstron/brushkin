package io.github.nielstron.brushkin.store

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.android.billingclient.api.*
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import io.github.nielstron.brushkin.BuildConfig
import io.github.nielstron.brushkin.R
import io.github.nielstron.brushkin.ui.Cmd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val REMOVE_ADS = "remove_ads"
private const val TEST_APP = "ca-app-pub-3940256099942544~3347511713"
private const val TEST_BANNER = "ca-app-pub-3940256099942544/9214589741"

class StoreIntegration(private val activity: ComponentActivity) {
    private val preferences = activity.getSharedPreferences("play_purchases", Context.MODE_PRIVATE)
    private var adFree by mutableStateOf(preferences.getBoolean(REMOVE_ADS, false))
    private var purchaseCheckComplete by mutableStateOf(false)
    private var adsReady by mutableStateOf(false)
    private var privacyRequired by mutableStateOf(false)
    private var dialog by mutableStateOf(false)
    private var product by mutableStateOf<ProductDetails?>(null)
    private var message by mutableStateOf("")
    private var purchaseInProgress by mutableStateOf(false)
    private var connecting = false
    private var consentStarted = false
    private var initializingAds = false
    private val consent = UserMessagingPlatform.getConsentInformation(activity)
    private val billing = BillingClient.newBuilder(activity)
        .setListener { result, purchases ->
            purchaseInProgress = false
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach(::processPurchase)
                BillingClient.BillingResponseCode.USER_CANCELED -> message = "Purchase cancelled."
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restore()
                else -> message = "Google Play couldn't complete the purchase. Please try again."
            }
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    // Debug builds can preview only Google's test ad without a Play-installed purchase catalog.
    private val preview get() = BuildConfig.DEBUG
    private val canShowAds get() = !adFree && adsReady && (purchaseCheckComplete || preview)

    val commands: List<Cmd>
        get() = buildList {
            add(Cmd("Brushkin", if (adFree) "Ads removed" else "Remove ads…", "one-time purchase") { dialog = true; queryProduct() })
            add(Cmd("Brushkin", "Restore purchase", "Google Play") { dialog = true; restore() })
            add(Cmd("Brushkin", "Privacy policy…") {
                activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://apiinnovators.github.io/brushkin/privacy.html")))
            })
            if (privacyRequired) add(Cmd("Brushkin", "Ad privacy choices…") {
                adsReady = false
                UserMessagingPlatform.showPrivacyOptionsForm(activity) {
                    updatePrivacy()
                    initializeAds()
                }
            })
        }

    fun start() {
        connect()
        if (preview) requestConsent()
    }

    fun resume() { if (billing.isReady) restore() else connect() }
    fun close() { billing.endConnection() }

    private fun connect() {
        if (connecting || billing.isReady) return
        connecting = true
        billing.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    restore()
                    queryProduct()
                } else message = "Connect to Google Play to check purchases."
            }
            override fun onBillingServiceDisconnected() { connecting = false }
        })
    }

    private fun queryProduct() {
        if (!billing.isReady) { connect(); return }
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(REMOVE_ADS)
                .setProductType(BillingClient.ProductType.INAPP).build()
        )).build()
        billing.queryProductDetailsAsync(params) { result, details ->
            activity.runOnUiThread {
                product = details.productDetailsList.firstOrNull { it.productId == REMOVE_ADS }
                if (result.responseCode != BillingClient.BillingResponseCode.OK || product == null)
                    message = if (preview) "Preview: purchase available after installing from Google Play." else "The purchase is unavailable. Please try again from Google Play."
            }
        }
    }

    private fun restore() {
        if (!billing.isReady) { connect(); return }
        billing.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases ->
            activity.runOnUiThread {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    message = "Couldn't check purchases. Your saved purchase is unchanged."
                    return@runOnUiThread
                }
                val matching = purchases.filter { REMOVE_ADS in it.products }
                val purchased = matching.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                if (purchased.isEmpty()) saveEntitlement(false)
                purchased.forEach(::processPurchase)
                purchaseCheckComplete = true
                if (matching.any { it.purchaseState == Purchase.PurchaseState.PENDING })
                    message = "Payment is pending. Ads will be removed when Google Play confirms it."
                else if (adFree) message = "Your ad-free purchase has been restored."
                else if (dialog && product != null) message = "No previous purchase found for this Google Play account."
                if (!adFree) requestConsent()
            }
        }
    }

    private fun processPurchase(purchase: Purchase) {
        if (REMOVE_ADS !in purchase.products) return
        if (purchase.packageName != activity.packageName) return
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            message = "Payment is pending. Ads will be removed when Google Play confirms it."
            return
        }
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (!verifyPurchase(activity.getString(R.string.play_billing_public_key), purchase.originalJson, purchase.signature)) {
            message = "Couldn't verify this purchase. Please restore it from Google Play."
            return
        }
        saveEntitlement(true)
        message = "Ads removed. Thank you for supporting Brushkin!"
        if (!purchase.isAcknowledged) billing.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        ) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) activity.runOnUiThread {
                message = "Ads removed. Please reconnect to Google Play to finish confirming your purchase."
            }
        }
    }

    private fun saveEntitlement(value: Boolean) {
        adFree = value
        preferences.edit().putBoolean(REMOVE_ADS, value).apply()
    }

    private fun buy() {
        val details = product ?: return
        val offer = details.oneTimePurchaseOfferDetailsList?.firstOrNull() ?: return
        val item = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
            .apply { offer.offerToken?.let { setOfferToken(it) } }.build()
        purchaseInProgress = true
        val result = billing.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(item)).build())
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            purchaseInProgress = false
            message = "Couldn't open Google Play checkout. Please try again."
        }
    }

    private fun requestConsent() {
        if (consentStarted || adFree) return
        consentStarted = true
        consent.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                updatePrivacy()
                initializeAds()
            }
        }, {
            updatePrivacy()
            initializeAds()
        })
    }

    private fun updatePrivacy() {
        privacyRequired = consent.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }

    private fun initializeAds() {
        if (adFree || initializingAds || !consent.canRequestAds()) return
        initializingAds = true
        activity.lifecycleScope.launch(Dispatchers.IO) {
            MobileAds.initialize(activity, InitializationConfig.Builder(if (preview) TEST_APP else activity.getString(R.string.admob_app_id)).build()) {}
            withContext(Dispatchers.Main) { adsReady = true; initializingAds = false }
        }
    }

    @Composable
    fun Banner(modifier: Modifier, covered: Boolean, onHeightChanged: (Int) -> Unit) {
        var loaded by remember { mutableStateOf(false) }
        val compactHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 600
        val show = canShowAds && loaded && !compactHeight
        LaunchedEffect(show) { onHeightChanged(if (show) 74 else 0) }
        DisposableEffect(Unit) { onDispose { onHeightChanged(0) } }
        if (!canShowAds || compactHeight) return
        val view = remember { AdView(activity) }
        DisposableEffect(view) {
            view.loadAd(BannerAdRequest.Builder(if (preview) TEST_BANNER else activity.getString(R.string.admob_banner_id), AdSize.BANNER).build(),
                object : AdLoadCallback<BannerAd> {
                    override fun onAdLoaded(ad: BannerAd) { activity.runOnUiThread { loaded = true } }
                    override fun onAdFailedToLoad(adError: LoadAdError) { activity.runOnUiThread { loaded = false } }
                })
            onDispose { view.destroy(); loaded = false }
        }
        // The fixed 320×50 banner has its own dark strip and a 12dp gap on both sides.
        // Pointer events in the surrounding strip must never paint on the canvas underneath.
        Box(modifier.fillMaxWidth().height(if (show) 74.dp else 0.dp).clipToBounds().background(Color(0xFF111113))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val left = (size.width - 320.dp.toPx()) / 2f
                    // Let the ad itself receive normal taps. Only its surrounding margin blocks input.
                    if (down.position.x !in left..(left + 320.dp.toPx()) || down.position.y !in 12.dp.toPx()..62.dp.toPx()) {
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
            },
            contentAlignment = Alignment.Center) {
            AndroidView(factory = { view }, modifier = Modifier.size(320.dp, 50.dp),
                update = { it.visibility = if (covered || dialog) android.view.View.INVISIBLE else android.view.View.VISIBLE })
        }
    }

    @Composable
    fun PurchaseDialog() {
        if (!dialog) return
        val price = product?.oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice
        AlertDialog(onDismissRequest = { dialog = false }, title = { Text(if (adFree) "Ads removed" else "Remove ads") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (adFree) "Brushkin is ad-free on this Google Play account." else "Remove the banner permanently with one purchase. No subscription. You can restore it on another device using the same Google Play account.")
                    if (preview && price == null) Text("Preview · US$5.00 one-time purchase")
                    if (message.isNotEmpty()) Text(message)
                    TextButton(onClick = { restore(); queryProduct() }) { Text("Restore purchase") }
                }
            },
            confirmButton = {
                if (!adFree) TextButton(enabled = price != null && !purchaseInProgress, onClick = ::buy) { Text(price?.let { "Remove ads · $it" } ?: "Checking Google Play…") }
                else TextButton(onClick = { dialog = false }) { Text("Done") }
            }, dismissButton = { if (!adFree) TextButton(onClick = { dialog = false }) { Text("Not now") } })
    }
}
