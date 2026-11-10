package com.cleartether.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.CardEmulation
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cleartether.app.core.share.NdefCodec
import com.cleartether.app.core.share.WifiCredentials
import com.cleartether.app.core.share.WifiQr
import com.cleartether.app.core.share.WifiSimpleConfig
import com.cleartether.app.data.AppMode
import com.cleartether.app.data.ClientStatus
import com.cleartether.app.data.HostErrorKind
import com.cleartether.app.data.HostStatus
import com.cleartether.app.service.NfcShareService
import com.cleartether.app.ui.ClearTetherAppUi
import com.cleartether.app.ui.MainViewModel
import com.cleartether.app.ui.PaywallReason
import com.cleartether.app.ui.PlatformActions
import com.cleartether.app.ui.theme.ClearTetherTheme
import com.cleartether.app.wifi.WifiDirectManager
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Single-activity host for the Compose UI. Everything that needs an Activity lives here:
 * runtime permissions, the Wi-Fi panel, VPN consent, the Play Billing sheet, the QR scanner,
 * NFC reader mode (client) and the preferred card-emulation service (host).
 */
class MainActivity : ComponentActivity(), PlatformActions {

    private val viewModel: MainViewModel by viewModels()
    private val graph by lazy { AppGraph.from(this) }
    private val nfcAdapter: NfcAdapter? by lazy { NfcAdapter.getDefaultAdapter(this) }
    private var pendingStart: AppMode? = null
    private var readerModeOn = false
    private var preferredHce = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val mode = pendingStart ?: return@registerForActivityResult
        pendingStart = null
        when (mode) {
            AppMode.HOST -> {
                val granted = WifiDirectManager.requiredPermissions().all { result[it] ?: isGranted(it) }
                if (granted) continueHostStart() else graph.runtime.host.value = HostStatus.Error(HostErrorKind.PERMISSION_MISSING)
            }
            AppMode.CLIENT -> continueClientStart()
        }
    }

    private val wifiPanelLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (WifiDirectManager(this).isWifiEnabled) viewModel.startHost()
        else graph.runtime.host.value = HostStatus.Error(HostErrorKind.WIFI_DISABLED)
    }

    private val vpnConsentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) viewModel.startClient() else viewModel.onVpnPermissionDenied()
    }

    override val nfcAvailable: Boolean get() = nfcAdapter?.isEnabled == true

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val debugPremium by graph.settings.debugPremium.collectAsStateWithLifecycle(initialValue = false)
            ClearTetherTheme {
                ClearTetherAppUi(viewModel, this, debugPremium)
            }
        }
        handleIntent(intent)
        observeNfcRoles()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onPause() {
        setReaderMode(false)
        setPreferredHce(false)
        super.onPause()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SHOW_PAYWALL, false) == true) {
            val reason = when (graph.session.state.value.limitReached) {
                com.cleartether.app.core.session.LimitReason.TIME -> PaywallReason.TIME_LIMIT
                com.cleartether.app.core.session.LimitReason.DATA -> PaywallReason.DATA_LIMIT
                null -> PaywallReason.UPGRADE
            }
            viewModel.showPaywall(reason)
            intent.removeExtra(EXTRA_SHOW_PAYWALL)
        }
    }

    /** NFC role follows the UI: reader on an idle client, card emulation on a running host. */
    private fun observeNfcRoles() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.state
                    .map { s ->
                        val reader = s.mode == AppMode.CLIENT && s.client !is ClientStatus.Connected && s.client !is ClientStatus.Connecting
                        val hce = s.mode == AppMode.HOST && s.host is HostStatus.Running && s.hostSettings?.nfcSharing == true
                        reader to hce
                    }
                    .distinctUntilChanged()
                    .collect { (reader, hce) ->
                        setReaderMode(reader)
                        setPreferredHce(hce)
                    }
            }
        }
    }

    private fun setReaderMode(enabled: Boolean) {
        val adapter = nfcAdapter ?: return
        if (enabled == readerModeOn) return
        runCatching {
            if (enabled) {
                adapter.enableReaderMode(this, ::onTagDiscovered, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B, null)
            } else {
                adapter.disableReaderMode(this)
            }
            readerModeOn = enabled
        }
    }

    private fun setPreferredHce(enabled: Boolean) {
        val adapter = nfcAdapter ?: return
        if (enabled == preferredHce) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) return
        runCatching {
            val emulation = CardEmulation.getInstance(adapter)
            if (enabled) emulation.setPreferredService(this, ComponentName(this, NfcShareService::class.java))
            else emulation.unsetPreferredService(this)
            preferredHce = enabled
        }
    }

    /** Reader-mode callback (binder thread): a tap on a ClearTether host or any Wi-Fi NFC tag. */
    private fun onTagDiscovered(tag: Tag) {
        val ndef = Ndef.get(tag) ?: return
        val credentials = runCatching {
            ndef.connect()
            val message = ndef.ndefMessage ?: ndef.cachedNdefMessage ?: return@runCatching null
            NdefCodec.decode(message.toByteArray())
                .firstOrNull { String(it.type, Charsets.US_ASCII) == WifiSimpleConfig.MIME_TYPE }
                ?.let { WifiSimpleConfig.decode(it.payload) }
        }.getOrNull().also { runCatching { ndef.close() } }
        if (credentials != null) {
            runOnUiThread {
                lifecycleScope.launch {
                    // Persist first so the VPN service reads the new host, then connect.
                    viewModel.saveScannedHost(credentials).join()
                    requestClientStart()
                }
            }
        }
    }

    // ------------------------------------------------------------------ PlatformActions

    override fun requestHostStart() {
        val missing = (WifiDirectManager.requiredPermissions() + notificationPermission()).filterNot(::isGranted)
        if (missing.isNotEmpty()) {
            pendingStart = AppMode.HOST
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        continueHostStart()
    }

    private fun continueHostStart() {
        val wifiDirect = WifiDirectManager(this)
        if (!wifiDirect.isSupported) {
            graph.runtime.host.value = HostStatus.Error(HostErrorKind.WIFI_DIRECT_UNSUPPORTED)
            return
        }
        if (!wifiDirect.isWifiEnabled) {
            // Wi-Fi Direct needs the Wi-Fi radio on (it doesn't have to join any network).
            Toast.makeText(this, R.string.toast_turn_on_wifi, Toast.LENGTH_LONG).show()
            wifiPanelLauncher.launch(Intent(Settings.Panel.ACTION_WIFI))
            return
        }
        viewModel.startHost()
    }

    override fun requestClientStart() {
        val optional = buildList {
            addAll(notificationPermission())
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.filterNot(::isGranted)
        if (optional.isNotEmpty()) {
            pendingStart = AppMode.CLIENT
            permissionLauncher.launch(optional.toTypedArray())
            return
        }
        continueClientStart()
    }

    private fun continueClientStart() {
        lifecycleScope.launch {
            val settings = graph.settings.clientSettings()
            if (settings.joinFromApp && !settings.hasCredentials) {
                Toast.makeText(this@MainActivity, R.string.toast_scan_first, Toast.LENGTH_LONG).show()
                scanQr()
                return@launch
            }
            val consent = VpnService.prepare(this@MainActivity)
            if (consent != null) vpnConsentLauncher.launch(consent) else viewModel.startClient()
        }
    }

    override fun scanQr() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val credentials = barcode.rawValue?.let(WifiQr::decode)
                    ?: barcode.wifi?.let { w -> w.ssid?.let { WifiCredentials(it, w.password.orEmpty()) } }
                if (credentials != null) {
                    viewModel.saveScannedHost(credentials)
                } else {
                    Toast.makeText(this, R.string.toast_not_wifi_qr, Toast.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, R.string.toast_scanner_unavailable, Toast.LENGTH_LONG).show()
            }
    }

    override fun purchase() {
        graph.billing.launchPurchase(this)
    }

    override fun copy(label: String, value: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(label, value)
        if (label == getString(R.string.label_password)) {
            // ClipDescription.EXTRA_IS_SENSITIVE (API 33); the literal key is honoured on older releases too.
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        clipboard.setPrimaryClip(clip)
        // Android 13+ shows its own clipboard confirmation.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, getString(R.string.toast_copied, label), Toast.LENGTH_SHORT).show()
    }

    override fun openWifiSettings() {
        wifiPanelLauncher.launch(Intent(Settings.Panel.ACTION_WIFI))
    }

    override fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    override fun openBatterySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            .onFailure { openAppSettings() }
    }

    private fun notificationPermission(): List<String> =
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_SHOW_PAYWALL = "com.cleartether.app.extra.SHOW_PAYWALL"
    }
}
