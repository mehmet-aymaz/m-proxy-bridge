package com.mproxy.bridge

import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.RouteInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_VPN = 100
        private const val TAG = "MainActivity"
    }

    private lateinit var logoRing: View
    private lateinit var logoRing2: View
    private lateinit var logoRing3: View
    private lateinit var logoInnerCircle: View
    private lateinit var blobActive: View
    private lateinit var headerStatusDot: View
    private lateinit var pingText: TextView
    private lateinit var uptimeText: TextView
    private lateinit var errorText: TextView
    private lateinit var btnConnect: Button
    private lateinit var centerIcon: ImageView
    private lateinit var centerSpinner: android.widget.ProgressBar
    private lateinit var logoStatusText: TextView
    private lateinit var cardIpValue: TextView
    private lateinit var cardVersionValue: TextView
    private lateinit var btnBackgroundOptimization: View
    private lateinit var bottomInfoText: TextView
    private lateinit var bottomStatusDot: View

    // Translation elements
    private lateinit var langFlagIcon: ImageView
    private lateinit var uptimeLabel: TextView
    private lateinit var cardIpLabel: TextView
    private lateinit var cardVersionLabel: TextView
    private lateinit var batteryOptLabel: TextView

    private var currentLang = "TR"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isReceiverRegistered = false
    private var pulseAnimatorSet: AnimatorSet? = null
    private var dotAnimatorSet: AnimatorSet? = null
    private var isFirstLoad = true

    private var vpnState = BridgeVpnService.STATE_DISCONNECTED
    private var vpnActive = false
    private var vpnStartTimestamp = 0L
    private var vpnLastIpAddress: String? = null

    private val pingHandler = Handler(Looper.getMainLooper())
    private val pingRunnable = object : Runnable {
        override fun run() {
            val state = vpnState
            if (state == BridgeVpnService.STATE_CONNECTED || state == BridgeVpnService.STATE_CONNECTING) {
                Thread({
                    val gatewayIp = getGatewayIp()
                    val startTime = System.currentTimeMillis()
                    val socket = java.net.Socket()
                    var success = false
                    try {
                        socket.connect(java.net.InetSocketAddress(gatewayIp, 10808), 2000)
                        success = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Ping connect failed: ${e.message}")
                    } finally {
                        try { socket.close() } catch (_: Exception) {}
                    }
                    val latency = System.currentTimeMillis() - startTime
                    mainHandler.post {
                        val currState = vpnState
                        if (currState == BridgeVpnService.STATE_CONNECTED || currState == BridgeVpnService.STATE_CONNECTING) {
                            if (success) {
                                pingText.text = "⚡ $latency ms"
                                pingText.visibility = View.VISIBLE
                            } else {
                                pingText.text = "⚡ -"
                                pingText.visibility = View.VISIBLE
                            }
                        }
                    }
                }, "bridge-ping-thread").start()
                pingHandler.postDelayed(this, 3000)
            } else {
                pingText.visibility = View.GONE
            }
        }
    }

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.mproxy.bridge.VPN_STATE") {
                val state = intent.getIntExtra("state", BridgeVpnService.STATE_DISCONNECTED)
                val error = intent.getStringExtra("error")
                vpnState = state
                vpnActive = intent.getBooleanExtra("active", false)
                vpnStartTimestamp = intent.getLongExtra("startTimestamp", 0L)
                val ipExtra = intent.getStringExtra("lastIpAddress")
                if (!ipExtra.isNullOrEmpty()) {
                    vpnLastIpAddress = ipExtra
                }
                updateUi(state, error)
            }
        }
    }

    private val uptimeRunnable = object : Runnable {
        override fun run() {
            // Guard: only continue if VPN is active and activity is alive
            if (!vpnActive || vpnStartTimestamp <= 0) {
                uptimeText.text = "00:00:00"
                return  // Stop the loop — no postDelayed, saves CPU
            }
            val elapsedSeconds = (System.currentTimeMillis() - vpnStartTimestamp) / 1000
            val hours   = elapsedSeconds / 3600
            val minutes = (elapsedSeconds % 3600) / 60
            val seconds = elapsedSeconds % 60
            uptimeText.text = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
            mainHandler.postDelayed(this, 1000) // reschedule only while active
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Portrait lock on phones; landscape allowed on tablets (sw600dp+)
        val smallestWidth = resources.configuration.smallestScreenWidthDp
        if (smallestWidth < 600) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        // else: tablets freely rotate

        // Edge-to-edge / immersive full-screen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        setContentView(R.layout.activity_main)

        // View bindings
        logoRing                = findViewById(R.id.logo_ring)
        logoRing2               = findViewById(R.id.logo_ring_2)
        logoRing3               = findViewById(R.id.logo_ring_3)
        logoInnerCircle         = findViewById(R.id.logo_inner_circle)
        blobActive              = findViewById(R.id.blob_active)
        headerStatusDot         = findViewById(R.id.header_status_dot)
        pingText                = findViewById(R.id.ping_text)
        uptimeText              = findViewById(R.id.uptime_text)
        errorText               = findViewById(R.id.error_text)
        btnConnect              = findViewById(R.id.btn_connect)
        centerIcon              = findViewById(R.id.center_icon)
        centerSpinner           = findViewById(R.id.center_spinner)
        logoStatusText          = findViewById(R.id.logo_status_text)
        cardIpValue             = findViewById(R.id.card_ip_value)
        cardVersionValue        = findViewById(R.id.card_version_value)
        btnBackgroundOptimization = findViewById(R.id.btn_background_optimization)
        bottomInfoText          = findViewById(R.id.bottom_info_text)
        bottomStatusDot         = findViewById(R.id.bottom_status_dot)
        langFlagIcon            = findViewById(R.id.lang_flag_icon)
        uptimeLabel             = findViewById(R.id.uptime_label)
        cardIpLabel             = findViewById(R.id.card_ip_label)
        cardVersionLabel        = findViewById(R.id.card_version_label)
        batteryOptLabel         = findViewById(R.id.battery_opt_label)

        // App version
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            cardVersionValue.text = "v" + pInfo.versionName
        } catch (e: Exception) {
            cardVersionValue.text = "v1.1.2"
        }

        val cardVersionContainer = findViewById<View>(R.id.card_version_container)
        cardVersionContainer?.setOnClickListener {
            checkAppUpdate(silent = false)
        }
        cardVersionValue.setOnClickListener {
            checkAppUpdate(silent = false)
        }

        // Check for updates silently on startup
        checkAppUpdate(silent = true)

        btnConnect.setOnClickListener {
            val prefs = getSharedPreferences("mproxy_bridge_prefs", Context.MODE_PRIVATE)
            if (vpnActive) {
                prefs.edit().putBoolean("should_auto_connect", false).apply()
                stopBridgeService()
            } else {
                prefs.edit().putBoolean("should_auto_connect", true).apply()
                prepareAndStartVpn()
            }
        }

        // Tapping the rocket circle also toggles connection
        val logoRingContainer = findViewById<android.view.View>(R.id.logo_ring_container)
        logoRingContainer.setOnClickListener {
            val prefs = getSharedPreferences("mproxy_bridge_prefs", Context.MODE_PRIVATE)
            if (vpnActive) {
                prefs.edit().putBoolean("should_auto_connect", false).apply()
                stopBridgeService()
            } else {
                prefs.edit().putBoolean("should_auto_connect", true).apply()
                prepareAndStartVpn()
            }
        }

        val batteryOptClickArea = findViewById<View>(R.id.battery_opt_click_area)
        batteryOptClickArea.setOnClickListener {
            val prefs = getSharedPreferences("mproxy_bridge_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("battery_opt_dismissed", true).apply()
            updateBatteryCard()
            openBatteryOptimization()
        }

        val btnCloseBatteryOpt = findViewById<View>(R.id.btn_close_battery_opt)
        btnCloseBatteryOpt.setOnClickListener {
            val prefs = getSharedPreferences("mproxy_bridge_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("battery_opt_dismissed", true).apply()
            updateBatteryCard()
        }

        // Language selector → custom BottomSheetDialog
        val langSelector = findViewById<View>(R.id.lang_selector)
        langSelector.setOnClickListener {
            showLanguageSelectorBottomSheet()
        }

        // Load persisted language
        val prefs = getSharedPreferences("mproxy_bridge_prefs", Context.MODE_PRIVATE)
        currentLang = prefs.getString("selected_language", "TR") ?: "TR"
        updateLanguageUi()

        // First-launch: automatically ask for battery optimization exemption
        val isFirstLaunch = prefs.getBoolean("first_launch_done", false).not()
        if (isFirstLaunch && !checkBatteryOptimization()) {
            prefs.edit().putBoolean("first_launch_done", true).apply()
            // Small delay so the UI is fully rendered before opening system dialog
            mainHandler.postDelayed({
                openBatteryOptimization()
            }, 800)
        } else if (isFirstLaunch) {
            prefs.edit().putBoolean("first_launch_done", true).apply()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isReceiverRegistered) {
            val filter = IntentFilter("com.mproxy.bridge.VPN_STATE")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(vpnStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(vpnStateReceiver, filter)
            }
            isReceiverRegistered = true
        }

        val serviceRunning = isServiceRunning(BridgeVpnService::class.java)
        if (serviceRunning) {
            vpnActive = BridgeVpnService.isActive
            vpnState = BridgeVpnService.connectionState
            vpnStartTimestamp = BridgeVpnService.startTimestamp
            vpnLastIpAddress = BridgeVpnService.lastIpAddress
        } else {
            vpnActive = false
            vpnState = BridgeVpnService.STATE_DISCONNECTED
            vpnStartTimestamp = 0L
            vpnLastIpAddress = null
        }

        val prefs = getSharedPreferences("mproxy_bridge_prefs", Context.MODE_PRIVATE)
        currentLang = prefs.getString("selected_language", "TR") ?: "TR"
        updateLanguageUi()

        updateUi(vpnState, null)
        isFirstLoad = false
        updateBatteryCard()

        val shouldAuto = prefs.getBoolean("should_auto_connect", true)
        if (shouldAuto && !vpnActive) {
            prepareAndStartVpn()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isReceiverRegistered) {
            unregisterReceiver(vpnStateReceiver)
            isReceiverRegistered = false
        }
        mainHandler.removeCallbacks(uptimeRunnable)
    }

    // ─── VPN Control ──────────────────────────────────────────────────────────

    private fun prepareAndStartVpn() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, REQUEST_VPN)
        } else {
            startBridgeService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) {
            startBridgeService()
        }
    }

    private fun startBridgeService() {
        val intent = Intent(this, BridgeVpnService::class.java).apply {
            action = BridgeVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopBridgeService() {
        val intent = Intent(this, BridgeVpnService::class.java).apply {
            action = BridgeVpnService.ACTION_STOP
        }
        startService(intent)
    }

    // ─── Language Selector BottomSheetDialog ──────────────────────────────────

    private fun showLanguageSelectorBottomSheet() {
        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val sheetView = LayoutInflater.from(this).inflate(R.layout.dialog_language_selector, null)

        // Close button
        sheetView.findViewById<View>(R.id.btn_close).setOnClickListener {
            dialog.dismiss()
        }

        // Turkish option
        val btnTr = sheetView.findViewById<LinearLayout>(R.id.btn_lang_tr)
        val checkTr = sheetView.findViewById<ImageView>(R.id.check_tr)

        // English option
        val btnEn = sheetView.findViewById<LinearLayout>(R.id.btn_lang_en)
        val checkEn = sheetView.findViewById<ImageView>(R.id.check_en)

        // Highlight current selection
        updateSheetSelection(sheetView, currentLang)

        btnTr.setOnClickListener {
            if (currentLang != "TR") {
                currentLang = "TR"
                saveLanguage("TR")
                updateLanguageUi()
            }
            updateSheetSelection(sheetView, "TR")
            // Small delay so user sees the checkmark before dismiss
            mainHandler.postDelayed({ dialog.dismiss() }, 180)
        }

        btnEn.setOnClickListener {
            if (currentLang != "EN") {
                currentLang = "EN"
                saveLanguage("EN")
                updateLanguageUi()
            }
            updateSheetSelection(sheetView, "EN")
            mainHandler.postDelayed({ dialog.dismiss() }, 180)
        }

        dialog.setContentView(sheetView)
        // Make bottom sheet fully transparent so our drawable shows
        val parent = sheetView.parent as? View
        parent?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        dialog.show()
    }

    private fun updateSheetSelection(sheetView: View, lang: String) {
        val btnTr   = sheetView.findViewById<LinearLayout>(R.id.btn_lang_tr)
        val checkTr = sheetView.findViewById<ImageView>(R.id.check_tr)
        val btnEn   = sheetView.findViewById<LinearLayout>(R.id.btn_lang_en)
        val checkEn = sheetView.findViewById<ImageView>(R.id.check_en)

        if (lang == "TR") {
            btnTr.setBackgroundResource(R.drawable.bg_lang_item_selected)
            checkTr.visibility = View.VISIBLE
            btnEn.setBackgroundResource(R.drawable.bg_lang_item_unselected)
            checkEn.visibility = View.GONE
        } else {
            btnEn.setBackgroundResource(R.drawable.bg_lang_item_selected)
            checkEn.visibility = View.VISIBLE
            btnTr.setBackgroundResource(R.drawable.bg_lang_item_unselected)
            checkTr.visibility = View.GONE
        }
    }

    private fun saveLanguage(lang: String) {
        getSharedPreferences("mproxy_bridge_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("selected_language", lang)
            .apply()
    }

    // ─── Language UI ──────────────────────────────────────────────────────────

    private fun updateLanguageUi() {
        if (currentLang == "TR") {
            langFlagIcon.setImageResource(R.drawable.ic_flag_tr)
            uptimeLabel.text      = "AKTİF ÇALIŞMA SÜRESİ"
            cardIpLabel.text      = "IP ADRESİ"
            cardVersionLabel.text = "VERSİYON"
            batteryOptLabel.text  = "Arka plan kısıtlamalarını devre dışı bırak"

            when (BridgeVpnService.connectionState) {
                BridgeVpnService.STATE_CONNECTED -> {
                    btnConnect.text     = "BAĞLANTIYI KES"
                    bottomInfoText.text = "Köprü üzerinden internet trafiği güvenli şekilde tünelleniyor."
                }
                BridgeVpnService.STATE_CONNECTING -> {
                    btnConnect.text     = "BAĞLANIYOR..."
                    bottomInfoText.text = "Köprü bağlantısı kuruluyor..."
                }
                else -> {
                    btnConnect.text     = "BAĞLANTIYI BAŞLAT"
                    bottomInfoText.text = "Köprü üzerinden internet trafiği tünellenmiyor."
                }
            }
        } else {
            langFlagIcon.setImageResource(R.drawable.ic_flag_uk)
            uptimeLabel.text      = "ACTIVE UPTIME"
            cardIpLabel.text      = "IP ADDRESS"
            cardVersionLabel.text = "VERSION"
            batteryOptLabel.text  = "Disable background restrictions"

            when (BridgeVpnService.connectionState) {
                BridgeVpnService.STATE_CONNECTED -> {
                    btnConnect.text     = "DISCONNECT"
                    bottomInfoText.text = "Internet traffic is securely tunneled through the bridge."
                }
                BridgeVpnService.STATE_CONNECTING -> {
                    btnConnect.text     = "CONNECTING..."
                    bottomInfoText.text = "Connecting to the bridge..."
                }
                else -> {
                    btnConnect.text     = "START CONNECTION"
                    bottomInfoText.text = "Internet traffic is not tunneled through the bridge."
                }
            }
        }
    }

    // ─── Main UI Update ───────────────────────────────────────────────────────

    private fun updateUi(state: Int, error: String?) {
        updateLanguageUi()

        // Apply distinct dynamic tints and alphas to concentric rings
        if (state == BridgeVpnService.STATE_CONNECTED || state == BridgeVpnService.STATE_CONNECTING) {
            logoRing.backgroundTintList  = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#3800E5FF")) // 22%
            logoRing2.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2100E5FF")) // 13%
            logoRing3.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1200E5FF")) // 7%
        } else {
            val passiveTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#12FFFFFF")) // 7% white
            logoRing.backgroundTintList  = passiveTint
            logoRing2.backgroundTintList = passiveTint
            logoRing3.backgroundTintList = passiveTint
        }

        // Card version value is always cyan
        cardVersionValue.setTextColor(getColor(R.color.primary))

        when (state) {
            BridgeVpnService.STATE_CONNECTED -> {
                if (isFirstLoad) {
                    headerStatusDot.alpha = 1f
                    bottomStatusDot.alpha = 1f
                    startDotBreathingAnimation()
                } else {
                    changeStatusDots(BridgeVpnService.STATE_CONNECTED)
                }
                logoInnerCircle.setBackgroundResource(R.drawable.orb_connected)
                btnConnect.setBackgroundResource(R.drawable.btn_rect_active)
                btnConnect.setTextColor(getColor(R.color.accent_green))

                uptimeText.setTextColor(getColor(R.color.text_primary)) // White when active

                centerIcon.visibility = View.VISIBLE
                centerSpinner.visibility = View.GONE
                centerIcon.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.primary))

                logoStatusText.text = if (currentLang == "TR") "AKTİF" else "ACTIVE"
                logoStatusText.setTextColor(getColor(R.color.primary))

                // IP Address value turns cyan when connected
                cardIpValue.setTextColor(getColor(R.color.primary))

                errorText.visibility = View.GONE

                mainHandler.removeCallbacks(uptimeRunnable)
                mainHandler.post(uptimeRunnable)

                // Show active ambient blob
                blobActive.visibility = View.VISIBLE
                blobActive.alpha = 1f

                // Start real ping checks
                pingHandler.removeCallbacks(pingRunnable)
                pingHandler.post(pingRunnable)

                // Start pulse animation on concentric rings
                startPulseAnimation()

                // Fetch WAN IP or use cache
                if (BridgeVpnService.lastIpAddress != null) {
                    cardIpValue.text = BridgeVpnService.lastIpAddress
                } else {
                    cardIpValue.text = "–"
                    fetchWanIp()
                }
            }
            BridgeVpnService.STATE_CONNECTING -> {
                if (isFirstLoad) {
                    headerStatusDot.alpha = 0f
                    bottomStatusDot.alpha = 0f
                    stopDotBreathingAnimation()
                } else {
                    changeStatusDots(BridgeVpnService.STATE_CONNECTING)
                }
                logoInnerCircle.setBackgroundResource(R.drawable.orb_disconnected)
                btnConnect.setBackgroundResource(R.drawable.btn_rect_connecting)
                btnConnect.setTextColor(android.graphics.Color.parseColor("#A600E5FF")) // 65% cyan

                uptimeText.setTextColor(android.graphics.Color.parseColor("#30FFFFFF")) // Dimmed

                centerIcon.visibility = View.GONE
                centerSpinner.visibility = View.VISIBLE

                logoStatusText.text = if (currentLang == "TR") "BAĞLANIYOR" else "CONNECTING"
                logoStatusText.setTextColor(getColor(R.color.text_secondary))

                // IP Address value is en-dash and dimmed when connecting
                cardIpValue.text = "–"
                cardIpValue.setTextColor(android.graphics.Color.parseColor("#60FFFFFF"))

                errorText.visibility = View.GONE

                mainHandler.removeCallbacks(uptimeRunnable)
                uptimeText.text = "00:00:00"

                // Hide active ambient blob
                blobActive.visibility = View.GONE

                // Start real ping checks
                pingHandler.removeCallbacks(pingRunnable)
                pingHandler.post(pingRunnable)

                // Start pulse animation on concentric rings
                startPulseAnimation()
            }
            else -> { // STATE_DISCONNECTED
                if (isFirstLoad) {
                    headerStatusDot.alpha = 0f
                    bottomStatusDot.alpha = 0f
                    stopDotBreathingAnimation()
                } else {
                    changeStatusDots(BridgeVpnService.STATE_DISCONNECTED)
                }
                logoInnerCircle.setBackgroundResource(R.drawable.orb_disconnected)
                btnConnect.setBackgroundResource(R.drawable.btn_rect_inactive)
                btnConnect.setTextColor(getColor(R.color.text_primary)) // White on inactive button

                uptimeText.setTextColor(android.graphics.Color.parseColor("#30FFFFFF")) // Dimmed

                centerIcon.visibility = View.VISIBLE
                centerSpinner.visibility = View.GONE
                centerIcon.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent_grey))

                logoStatusText.text = if (currentLang == "TR") "PASİF" else "PASSIVE"
                logoStatusText.setTextColor(getColor(R.color.text_secondary))

                // IP Address value is en-dash and dimmed when disconnected
                cardIpValue.text = "–"
                cardIpValue.setTextColor(android.graphics.Color.parseColor("#60FFFFFF"))

                uptimeText.text = "00:00:00"

                mainHandler.removeCallbacks(uptimeRunnable)

                // Stop real ping checks
                pingHandler.removeCallbacks(pingRunnable)
                pingText.visibility = View.GONE

                // Hide active ambient blob
                blobActive.visibility = View.GONE

                // Stop pulse animation
                stopPulseAnimation()

                if (!error.isNullOrEmpty()) {
                    errorText.text = error
                    errorText.visibility = View.VISIBLE
                } else {
                    errorText.visibility = View.GONE
                }
            }
        }
    }

    // ─── Logo Pulse Animation ─────────────────────────────────────────────────

    private fun startPulseAnimation() {
        stopPulseAnimation()

        val repeatInfinite = android.animation.ValueAnimator.INFINITE
        val interp = AccelerateDecelerateInterpolator()

        // Ring 1 pulse (delay 0ms)
        val scaleX1 = ObjectAnimator.ofFloat(logoRing, "scaleX", 1f, 1.05f, 1f).apply {
            duration = 2600; repeatCount = repeatInfinite; interpolator = interp
        }
        val scaleY1 = ObjectAnimator.ofFloat(logoRing, "scaleY", 1f, 1.05f, 1f).apply {
            duration = 2600; repeatCount = repeatInfinite; interpolator = interp
        }
        val alpha1 = ObjectAnimator.ofFloat(logoRing, "alpha", 1f, 0.6f, 1f).apply {
            duration = 2600; repeatCount = repeatInfinite; interpolator = interp
        }

        // Ring 2 pulse (delay 250ms)
        val scaleX2 = ObjectAnimator.ofFloat(logoRing2, "scaleX", 1f, 1.05f, 1f).apply {
            duration = 2600; repeatCount = repeatInfinite; interpolator = interp; startDelay = 250
        }
        val scaleY2 = ObjectAnimator.ofFloat(logoRing2, "scaleY", 1f, 1.05f, 1f).apply {
            duration = 2600; repeatCount = repeatInfinite; interpolator = interp; startDelay = 250
        }
        val alpha2 = ObjectAnimator.ofFloat(logoRing2, "alpha", 1f, 0.6f, 1f).apply {
            duration = 2600; repeatCount = repeatInfinite; interpolator = interp; startDelay = 250
        }

        // Ring 3 pulse (delay 500ms)
        val scaleX3 = ObjectAnimator.ofFloat(logoRing3, "scaleX", 1f, 1.05f, 1f).apply {
            duration = 2600; repeatCount = repeatInfinite; interpolator = interp; startDelay = 500
        }
        val scaleY3 = ObjectAnimator.ofFloat(logoRing3, "scaleY", 1f, 1.05f, 1f).apply {
            duration = 2600; repeatCount = repeatInfinite; interpolator = interp; startDelay = 500
        }
        val alpha3 = ObjectAnimator.ofFloat(logoRing3, "alpha", 1f, 0.6f, 1f).apply {
            duration = 2600; repeatCount = repeatInfinite; interpolator = interp; startDelay = 500
        }

        val set = AnimatorSet().apply {
            playTogether(
                scaleX1, scaleY1, alpha1,
                scaleX2, scaleY2, alpha2,
                scaleX3, scaleY3, alpha3
            )
        }
        set.start()
        pulseAnimatorSet = set
    }

    private fun stopPulseAnimation() {
        pulseAnimatorSet?.cancel()
        pulseAnimatorSet = null

        // Reset transforms so all concentric rings are visible but static
        logoRing.scaleX = 1f
        logoRing.scaleY = 1f
        logoRing.alpha  = 1f

        logoRing2.scaleX = 1f
        logoRing2.scaleY = 1f
        logoRing2.alpha  = 1f

        logoRing3.scaleX = 1f
        logoRing3.scaleY = 1f
        logoRing3.alpha  = 1f
    }

    private fun startDotBreathingAnimation() {
        stopDotBreathingAnimation()

        val repeatInfinite = android.animation.ValueAnimator.INFINITE
        val interp = AccelerateDecelerateInterpolator()

        val animHeader = ObjectAnimator.ofFloat(headerStatusDot, "alpha", 1f, 0.15f, 1f).apply {
            duration = 2000; repeatCount = repeatInfinite; interpolator = interp
        }
        val animBottom = ObjectAnimator.ofFloat(bottomStatusDot, "alpha", 1f, 0.15f, 1f).apply {
            duration = 2000; repeatCount = repeatInfinite; interpolator = interp
        }

        dotAnimatorSet = AnimatorSet().apply {
            playTogether(animHeader, animBottom)
            start()
        }
    }

    private fun stopDotBreathingAnimation() {
        dotAnimatorSet?.cancel()
        dotAnimatorSet = null
    }

    private fun changeStatusDots(state: Int) {
        stopDotBreathingAnimation()

        val targetAlpha = when (state) {
            BridgeVpnService.STATE_CONNECTED -> 1f
            else -> 0f
        }

        // Fade both dots to targetAlpha
        fadeTransitionDot(headerStatusDot, targetAlpha) {
            if (state == BridgeVpnService.STATE_CONNECTED) {
                startDotBreathingAnimation()
            }
        }
        fadeTransitionDot(bottomStatusDot, targetAlpha, null)
    }

    private fun fadeTransitionDot(dotView: View, targetAlpha: Float, onComplete: (() -> Unit)?) {
        dotView.animate()
            .alpha(targetAlpha)
            .setDuration(800)
            .withEndAction {
                onComplete?.invoke()
            }
            .start()
    }

    // ─── Network Helpers ──────────────────────────────────────────────────────

    private fun getGatewayIp(): String {
        try {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            val linkProps = cm?.getLinkProperties(activeNetwork)
            for (route in linkProps?.routes ?: emptyList<RouteInfo>()) {
                if (route.isDefaultRoute && route.gateway is java.net.Inet4Address) {
                    val ip = route.gateway?.hostAddress
                    if (!ip.isNullOrEmpty() && ip != "0.0.0.0") {
                        return ip
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcpInfo = wifiManager?.dhcpInfo
            if (dhcpInfo != null && dhcpInfo.gateway != 0) {
                val ip = dhcpInfo.gateway
                return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting gateway IP: ${e.message}")
        }
        return "192.168.49.1"
    }


    private fun fetchWanIp() {
        Thread {
            try { Thread.sleep(2000) } catch (_: Exception) {}
            var ipAddress = "---.---.---.---"
            var conn: HttpURLConnection? = null
            try {
                val gatewayIp = getGatewayIp()
                val proxy = java.net.Proxy(
                    java.net.Proxy.Type.SOCKS,
                    java.net.InetSocketAddress(gatewayIp, 10808)
                )
                val url = URL("https://api.ipify.org")
                conn = url.openConnection(proxy) as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout    = 4000
                conn.connect()
                if (conn.responseCode == 200) {
                    ipAddress = String(conn.inputStream.readBytes()).trim()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching WAN IP: ${e.message}")
            } finally {
                conn?.disconnect()
            }

            mainHandler.post {
                if (BridgeVpnService.isActive) {
                    cardIpValue.text = ipAddress
                    BridgeVpnService.lastIpAddress = ipAddress
                    
                    // Widget önbelleğine kaydet ve widget'ı tetikle
                    getSharedPreferences("mproxy_bridge_widget_cache", Context.MODE_PRIVATE)
                        .edit().putString("cached_ip", ipAddress).apply()
                    MProxyBridgeWidgetProvider.triggerUpdate(this)
                }
            }
        }.start()
    }

    // ─── Battery Optimization ─────────────────────────────────────────────────

    private fun openBatteryOptimization() {
        var openedAutostart = false
        val manufacturer = Build.MANUFACTURER.lowercase(java.util.Locale.ROOT)
        
        try {
            val intent = Intent()
            when {
                manufacturer.contains("xiaomi") -> {
                    intent.component = android.content.ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                }
                manufacturer.contains("huawei") -> {
                    intent.component = android.content.ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                }
                manufacturer.contains("oppo") -> {
                    intent.component = android.content.ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.startupapp.StartupAppListActivity"
                    )
                }
                manufacturer.contains("vivo") -> {
                    intent.component = android.content.ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    )
                }
            }
            if (intent.component != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                openedAutostart = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open OEM autostart settings: ${e.message}")
        }

        if (!openedAutostart) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open battery optimization: ${e.message}")
            }
        }
    }

    private fun checkBatteryOptimization(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            return pm.isIgnoringBatteryOptimizations(packageName)
        }
        return true
    }

    private fun updateBatteryCard() {
        val prefs = getSharedPreferences("mproxy_bridge_prefs", Context.MODE_PRIVATE)
        val isDismissed = prefs.getBoolean("battery_opt_dismissed", false)
        if (isDismissed || checkBatteryOptimization()) {
            btnBackgroundOptimization.visibility = View.GONE
        } else {
            btnBackgroundOptimization.visibility = View.VISIBLE
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
                if (serviceClass.name == service.service.className) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking service running status: ${e.message}")
        }
        return false
    }

    // ─── App Update Checker ───────────────────────────────────────────────────

    fun checkAppUpdate(silent: Boolean) {
        val currentVersion = try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: "1.1.2"
        } catch (e: Exception) {
            "1.1.2"
        }

        if (!silent) {
            val msg = if (currentLang == "TR") "Güncellemeler denetleniyor..." else "Checking for updates..."
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        val executor = Executors.newSingleThreadExecutor()
        val handler = Handler(Looper.getMainLooper())

        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("https://api.github.com/repos/mehmet-aymaz/m-proxy-bridge/releases/latest")
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", "M-Proxy-Bridge-Android")
                connection.connect()

                if (connection.responseCode == 200) {
                    val responseBody = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    val json = JSONObject(responseBody)
                    val latestVersion = json.optString("tag_name", "").replace("v", "").trim()
                    val changelog = json.optString("body", "")

                    val assets = json.optJSONArray("assets")
                    var apkUrl: String? = null
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url", "")
                                break
                            }
                        }
                    }

                    if (latestVersion.isNotEmpty() && apkUrl != null) {
                        val hasUpdate = isVersionNewer(currentVersion, latestVersion)
                        if (hasUpdate) {
                            handler.post {
                                showUpdateDialog(latestVersion, changelog, apkUrl)
                            }
                        } else {
                            if (!silent) {
                                handler.post {
                                    val msg = if (currentLang == "TR") "Uygulama güncel (v$currentVersion)" else "App is up to date (v$currentVersion)"
                                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else {
                        if (!silent) {
                            handler.post {
                                val msg = if (currentLang == "TR") "Sürüm bilgisi alınamadı." else "Could not fetch version info."
                                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    if (!silent) {
                        handler.post {
                            val msg = if (currentLang == "TR") "API Hatası: ${connection.responseCode}" else "API Error: ${connection.responseCode}"
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed: ${e.message}", e)
                if (!silent) {
                    handler.post {
                        val msg = if (currentLang == "TR") "Bağlantı Hatası: ${e.message}" else "Connection Error: ${e.message}"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun isVersionNewer(current: String, latest: String): Boolean {
        val cleanCurrent = current.replace(Regex("[^0-9.]"), "")
        val cleanLatest = latest.replace(Regex("[^0-9.]"), "")

        val currentParts = cleanCurrent.split(".").map { it.toIntOrNull() ?: 0 }
        val latestParts = cleanLatest.split(".").map { it.toIntOrNull() ?: 0 }

        val maxLength = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until maxLength) {
            val curr = currentParts.getOrNull(i) ?: 0
            val lat = latestParts.getOrNull(i) ?: 0
            if (lat > curr) return true
            if (curr > lat) return false
        }
        return false
    }

    private fun showUpdateDialog(latestVersion: String, changelog: String, apkUrl: String) {
        if (isFinishing || isDestroyed) return

        val dialog = AlertDialog.Builder(this).create()
        val density = resources.displayMetrics.density
        fun Int.dp(): Int = (this * density).toInt()

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#0B132B"))
                cornerRadius = 24 * density
                setStroke((1 * density).toInt(), Color.parseColor("#1E293B"))
            }
            setPadding(24.dp(), 24.dp(), 24.dp(), 24.dp())
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Title
        val txtTitle = TextView(this).apply {
            text = if (currentLang == "TR") "Yeni Sürüm Mevcut" else "Update Available"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#00E5FF"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 12.dp()
            }
        }
        rootLayout.addView(txtTitle)

        // Message
        val txtMsg = TextView(this).apply {
            text = if (currentLang == "TR") {
                "M-Proxy Bridge v$latestVersion sürümü indirilebilir. Şimdi güncellemek ister misiniz?"
            } else {
                "M-Proxy Bridge v$latestVersion is available. Would you like to update now?"
            }
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 16.dp()
            }
        }
        rootLayout.addView(txtMsg)

        // Changelog
        if (changelog.isNotBlank()) {
            val lblChangelog = TextView(this).apply {
                text = if (currentLang == "TR") "Değişiklikler:" else "Changelog:"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#80FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 4.dp()
                }
            }
            rootLayout.addView(lblChangelog)

            val scrollView = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 110.dp()).apply {
                    bottomMargin = 20.dp()
                }
            }
            val txtChangelog = TextView(this).apply {
                text = changelog
                textSize = 12f
                setTextColor(Color.parseColor("#CCCCCC"))
                setLineSpacing(2f, 1.1f)
            }
            scrollView.addView(txtChangelog)
            rootLayout.addView(scrollView)
        }

        // Buttons
        val buttonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val btnCancel = Button(this).apply {
            text = if (currentLang == "TR") "DAHA SONRA" else "LATER"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#14FFFFFF"))
                cornerRadius = 14 * density
                setStroke((1 * density).toInt(), Color.parseColor("#22FFFFFF"))
            }
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            setOnClickListener { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 8.dp()
            }
        }
        buttonsLayout.addView(btnCancel)

        val btnUpdate = Button(this).apply {
            text = if (currentLang == "TR") "GÜNCELLE" else "UPDATE"
            setTextColor(Color.parseColor("#060C18"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#00E5FF"))
                cornerRadius = 14 * density
            }
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            setOnClickListener {
                dialog.dismiss()
                downloadAndInstallApk(apkUrl)
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        buttonsLayout.addView(btnUpdate)
        rootLayout.addView(buttonsLayout)

        dialog.setView(rootLayout)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes.blurBehindRadius = 45
            }
            val wlp = WindowManager.LayoutParams()
            wlp.copyFrom(attributes)
            wlp.width = (340 * density).toInt()
            attributes = wlp
        }
    }

    private fun downloadAndInstallApk(apkUrl: String) {
        val density = resources.displayMetrics.density
        fun Int.dp(): Int = (this * density).toInt()

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#0B132B"))
                cornerRadius = 24 * density
                setStroke((1 * density).toInt(), Color.parseColor("#1E293B"))
            }
            setPadding(24.dp(), 24.dp(), 24.dp(), 24.dp())
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val txtTitle = TextView(this).apply {
            text = if (currentLang == "TR") "Güncelleme İndiriliyor" else "Downloading Update"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#00E5FF"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 12.dp()
            }
        }
        rootLayout.addView(txtTitle)

        val textView = TextView(this).apply {
            text = if (currentLang == "TR") "Lütfen bekleyin..." else "Please wait..."
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 8.dp()
            }
        }
        rootLayout.addView(textView)

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF"))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#30FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 16.dp()
            }
        }
        rootLayout.addView(progressBar)

        val progressDialog = AlertDialog.Builder(this)
            .setView(rootLayout)
            .setCancelable(false)
            .create()
        progressDialog.show()
        progressDialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes.blurBehindRadius = 45
            }
            val wlp = WindowManager.LayoutParams()
            wlp.copyFrom(attributes)
            wlp.width = (340 * density).toInt()
            attributes = wlp
        }

        val executor = Executors.newSingleThreadExecutor()
        val handler = Handler(Looper.getMainLooper())

        executor.execute {
            var connection: HttpURLConnection? = null
            var inputStream: java.io.InputStream? = null
            var outputStream: FileOutputStream? = null
            try {
                val url = URL(apkUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("Sunucu hata döndü: ${connection.responseCode}")
                }

                val fileLength = connection.contentLength
                inputStream = connection.inputStream
                val tempFile = File(cacheDir, "update.apk")
                if (tempFile.exists()) {
                    tempFile.delete()
                }

                outputStream = FileOutputStream(tempFile)
                val data = ByteArray(4096)
                var total: Long = 0
                var count: Int
                while (inputStream.read(data).also { count = it } != -1) {
                    total += count
                    if (fileLength > 0) {
                        val progress = (total * 100 / fileLength).toInt()
                        val kbDownloaded = total / 1024
                        val kbTotal = fileLength / 1024
                        handler.post {
                            progressBar.progress = progress
                            textView.text = if (currentLang == "TR") {
                                "İndiriliyor: $kbDownloaded KB / $kbTotal KB ($progress%)"
                            } else {
                                "Downloading: $kbDownloaded KB / $kbTotal KB ($progress%)"
                            }
                        }
                    } else {
                        val kbDownloaded = total / 1024
                        handler.post {
                            progressBar.isIndeterminate = true
                            textView.text = if (currentLang == "TR") {
                                "İndiriliyor: $kbDownloaded KB"
                            } else {
                                "Downloading: $kbDownloaded KB"
                            }
                        }
                    }
                    outputStream.write(data, 0, count)
                }

                handler.post {
                    progressDialog.dismiss()
                    installApk(tempFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Apk download failed: ${e.message}", e)
                handler.post {
                    progressDialog.dismiss()
                    val msg = if (currentLang == "TR") "İndirme hatası: ${e.message}" else "Download error: ${e.message}"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            } finally {
                try { outputStream?.close() } catch (_: Exception) {}
                try { inputStream?.close() } catch (_: Exception) {}
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    private fun installApk(file: File) {
        if (!file.exists()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(settingsIntent)
                val msg = if (currentLang == "TR") {
                    "Lütfen bilinmeyen uygulamaları yükleme iznini verin ve tekrar deneyin."
                } else {
                    "Please allow installing unknown apps and try again."
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                return
            }
        }

        val intent = Intent(Intent.ACTION_VIEW)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val authority = "$packageName.fileprovider"
            val apkUri = FileProvider.getUriForFile(this, authority, file)
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
        } else {
            intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
        }

        startActivity(intent)
    }
}
