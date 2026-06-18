package com.mproxy.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import libbox.BoxService
import libbox.Libbox
import libbox.SetupOptions
import java.net.InetSocketAddress
import java.net.Socket

class BridgeVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.mproxy.bridge.START"
        const val ACTION_STOP = "com.mproxy.bridge.STOP"
        const val NOTIFICATION_CHANNEL_ID = "mproxy_bridge_channel"
        const val NOTIFICATION_ID = 2
        private const val TAG = "BridgeVPN"

        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTING = 1
        const val STATE_CONNECTED = 2

        @Volatile
        var isActive = false
            internal set

        @Volatile
        var connectionState = STATE_DISCONNECTED
            internal set

        @Volatile
        var libboxReady = false
            private set

        @Volatile
        var startTimestamp = 0L

        @Volatile
        var lastIpAddress: String? = null
    }

    @Volatile
    private var boxService: BoxService? = null
    @Volatile
    private var platformInterface: BoxPlatformInterface? = null
    @Volatile
    private var boxThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile
    private var isPrecheckRunning = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val retryRunnable = Runnable {
        Log.d(TAG, "Retrying SOCKS5 bridge connection...")
        startVpn()
    }

    private var isTimeoutScheduled = false
    private val disconnectTimeoutRunnable = Runnable {
        Log.d(TAG, "5 minutes passed without connecting to the correct Wi-Fi. Stopping service to save battery.")
        stopVpn()
    }

    private fun startDisconnectTimeout() {
        if (!isTimeoutScheduled) {
            Log.d(TAG, "Scheduling 5-minute disconnect timeout")
            mainHandler.postDelayed(disconnectTimeoutRunnable, 300000) // 5 minutes (300,000 ms)
            isTimeoutScheduled = true
        }
    }

    private fun cancelDisconnectTimeout() {
        if (isTimeoutScheduled) {
            Log.d(TAG, "Cancelling 5-minute disconnect timeout")
            mainHandler.removeCallbacks(disconnectTimeoutRunnable)
            isTimeoutScheduled = false
        }
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MProxyBridge::WakeLock")
                wakeLock?.acquire()
            }
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MProxyBridge::WifiLock")
                } else {
                    @Suppress("DEPRECATION")
                    wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "MProxyBridge::WifiLock")
                }
                wifiLock?.acquire()
            }
            Log.d(TAG, "Acquired WakeLock and WifiLock")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire locks: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            wifiLock = null
            Log.d(TAG, "Released WakeLock and WifiLock")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release locks: ${e.message}")
        }
    }

    private fun stopVpnEngineOnly() {
        try {
            platformInterface?.closeTun()
        } catch (e: Throwable) {
            Log.e(TAG, "Error closing tun: ${e.message}")
        }
        try {
            boxService?.close()
        } catch (_: Throwable) {}
        boxService = null

        boxThread?.let { t ->
            try { t.join(1000) } catch (_: Throwable) {}
        }
        boxThread = null
        platformInterface = null
    }

    private fun getGatewayIp(): String {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcpInfo = wifiManager?.dhcpInfo
            if (dhcpInfo != null && dhcpInfo.gateway != 0) {
                val ipInt = dhcpInfo.gateway
                val ip = "${ipInt and 0xFF}.${(ipInt shr 8) and 0xFF}.${(ipInt shr 16) and 0xFF}.${(ipInt shr 24) and 0xFF}"
                Log.d(TAG, "Detected gateway IP: $ip")
                return ip
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting gateway IP: ${e.message}")
        }
        Log.d(TAG, "Using fallback gateway IP: 192.168.49.1")
        return "192.168.49.1"
    }

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (isActive) {
                Thread({
                    val gatewayIp = getGatewayIp()
                    val reachable = checkSocksReachable(gatewayIp)
                    if (!reachable && isActive) {
                        Log.w(TAG, "SOCKS5 bridge at $gatewayIp:10808 is no longer reachable. Reconnecting...")
                        mainHandler.post {
                            if (isActive) {
                                val prefs = getSharedPreferences("mproxy_bridge_prefs", Context.MODE_PRIVATE)
                                val shouldAuto = prefs.getBoolean("should_auto_connect", true)
                                if (shouldAuto) {
                                    broadcastState(STATE_CONNECTING, "Ana telefon bağlantısı kesildi. Yeniden aranıyor...")
                                    updateNotification("Hotspot aranıyor... Wi-Fi bağlantınızı kontrol edin.")
                                    stopVpnEngineOnly()
                                    
                                    // Release locks while waiting
                                    releaseLocks()
                                    // Schedule 5-minute timeout if not already scheduled
                                    startDisconnectTimeout()
                                    
                                    if (gatewayIp == "192.168.49.1") {
                                        mainHandler.removeCallbacks(retryRunnable)
                                        mainHandler.postDelayed(retryRunnable, 250)
                                    }
                                } else {
                                    broadcastState(STATE_DISCONNECTED, "Ana telefon bağlantısı kesildi.")
                                    showDisconnectionNotification()
                                    stopVpn()
                                }
                            }
                        }
                    } else if (isActive) {
                        mainHandler.postDelayed(this, 5000)
                    }
                }, "bridge-connection-check").start()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "=== BridgeVpnService onCreate ===")
        createNotificationChannel()
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback = object : ConnectivityManager.NetworkCallback() {

                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.d(TAG, "Network available, checking if it is the target hotspot...")
                    val prefs = getSharedPreferences("mproxy_bridge_prefs", Context.MODE_PRIVATE)
                    val shouldAuto = prefs.getBoolean("should_auto_connect", true)
                    if (!shouldAuto) {
                        Log.d(TAG, "Auto-connect disabled, ignoring network event.")
                        return
                    }
                    // Small delay so DHCP info is ready before we read the gateway
                    mainHandler.postDelayed({
                        if (isTargetHotspot()) {
                            Log.d(TAG, "Target hotspot confirmed via gateway IP. Auto-starting tunnel.")
                            cancelDisconnectTimeout()
                            if (!isActive && !isPrecheckRunning) {
                                startVpn()
                            } else if (connectionState == STATE_CONNECTING) {
                                mainHandler.removeCallbacks(retryRunnable)
                                mainHandler.post(retryRunnable)
                            }
                        } else {
                            Log.d(TAG, "Connected to a non-target network (gateway != 192.168.49.1). Starting disconnect timeout.")
                            // Not our hotspot — stop tunnel if running, start timeout
                            if (isActive) {
                                stopVpnEngineOnly()
                                releaseLocks()
                                broadcastState(STATE_CONNECTING, "Hedef hotspot bekleniyor...")
                                updateNotification("Hedef hotspot aranıyor...")
                            }
                            startDisconnectTimeout()
                        }
                    }, 1500)
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.d(TAG, "Network lost.")
                    val prefs = getSharedPreferences("mproxy_bridge_prefs", Context.MODE_PRIVATE)
                    val shouldAuto = prefs.getBoolean("should_auto_connect", true)
                    mainHandler.post {
                        if (isActive) {
                            Log.d(TAG, "Tunnel was active, network lost — stopping engine and entering retry mode.")
                            stopVpnEngineOnly()
                            releaseLocks()
                            if (shouldAuto) {
                                broadcastState(STATE_CONNECTING, "Ağ bağlantısı kesildi. Yeniden aranıyor...")
                                updateNotification("Ağ bekleniyor... Hotspot'a bağlanın.")
                                startDisconnectTimeout()
                            } else {
                                broadcastState(STATE_DISCONNECTED, "Ağ bağlantısı kesildi.")
                                showDisconnectionNotification()
                                stopVpn()
                            }
                        } else if (shouldAuto) {
                            // Already in retry mode; start/extend timeout
                            startDisconnectTimeout()
                        }
                    }
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    Log.d(TAG, "Network unavailable.")
                }
            }

            // Register for Wi-Fi networks specifically so we get events when hotspot appears
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            Log.d(TAG, "NetworkCallback registered for Wi-Fi transport.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    /**
     * Returns true when the currently connected Wi-Fi network is the target M-Proxy hotspot.
     * Primary check: gateway IP == 192.168.49.1 (Wi-Fi Direct default gateway).
     * Secondary check (Android 8+, requires ACCESS_FINE_LOCATION at runtime): SSID/BSSID match.
     */
    private fun isTargetHotspot(): Boolean {
        return try {
            val gatewayIp = getGatewayIp()
            if (gatewayIp == "192.168.49.1") {
                true
            } else {
                // Fallback: try SSID match if location permission is granted
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    val ssid = wifiManager?.connectionInfo?.ssid?.trim('"') ?: ""
                    val targetSsid = getSharedPreferences("mproxy_bridge_prefs", Context.MODE_PRIVATE)
                        .getString("target_ssid", "DIRECT-") ?: "DIRECT-"
                    ssid.startsWith(targetSsid)
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking target hotspot: ${e.message}")
            false
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.unregisterNetworkCallback(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister network callback: ${e.message}")
        }
        networkCallback = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")
        if (intent == null) {
            val prefs = getSharedPreferences("mproxy_bridge_prefs", Context.MODE_PRIVATE)
            val shouldAuto = prefs.getBoolean("should_auto_connect", true)
            if (shouldAuto) {
                Log.d(TAG, "System restarted service with null intent, restarting VPN")
                startVpn()
            } else {
                Log.d(TAG, "System restarted service with null intent but auto-connect is disabled, stopping self")
                stopVpn()
            }
            return START_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                startVpn()
            }
            ACTION_STOP -> {
                stopVpn()
            }
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (boxService != null) {
            Log.d(TAG, "VPN is already running, ignoring start request")
            return
        }
        if (isPrecheckRunning) {
            Log.d(TAG, "Pre-check is already running, ignoring")
            return
        }
        isPrecheckRunning = true
        acquireLocks()

        broadcastState(STATE_CONNECTING, null) // clear error/state on main screen

        try {
            startForeground(NOTIFICATION_ID, createNotification("Bağlantı kontrol ediliyor..."))
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start foreground immediately: ${e.message}")
        }

        Thread({
            try {
                val gatewayIp = getGatewayIp()
                val reachable = checkSocksReachable(gatewayIp)
                if (!reachable) {
                    Log.e(TAG, "SOCKS5 bridge at $gatewayIp:10808 is unreachable")
                    mainHandler.post {
                        val prefs = getSharedPreferences("mproxy_bridge_prefs", Context.MODE_PRIVATE)
                        val shouldAuto = prefs.getBoolean("should_auto_connect", true)
                        if (shouldAuto) {
                            broadcastState(STATE_CONNECTING, "M-Proxy hotspot aranıyor...")
                            updateNotification("Hotspot aranıyor... Wi-Fi bağlantınızı kontrol edin.")
                            
                            // Release locks while waiting
                            releaseLocks()
                            // Schedule 5-minute timeout if not already scheduled
                            startDisconnectTimeout()
                            
                            if (gatewayIp == "192.168.49.1") {
                                mainHandler.removeCallbacks(retryRunnable)
                                mainHandler.postDelayed(retryRunnable, 250)
                            }
                        } else {
                            broadcastState(STATE_DISCONNECTED, "Ana telefonun M-Proxy hotspot'u bulunamadı. Lütfen Wi-Fi Direct bağlantısını kontrol edin.")
                            stopVpn()
                        }
                    }
                    return@Thread
                }

                mainHandler.post {
                    runVpnEngine(gatewayIp)
                }
            } finally {
                isPrecheckRunning = false
            }
        }, "vpn-prestart-check").start()
    }

    private fun runVpnEngine(gatewayIp: String) {
        try {
            // Step 2: Setup libbox
            if (!libboxReady) {
                val basePath = cacheDir.absolutePath
                val workingPath = filesDir.absolutePath
                val tempPath = cacheDir.absolutePath

                val setupOpts = SetupOptions()
                setupOpts.setBasePath(basePath)
                setupOpts.setWorkingPath(workingPath)
                setupOpts.setTempPath(tempPath)

                Libbox.setup(setupOpts)
                libboxReady = true
                Log.d(TAG, "Libbox setup completed successfully")
            }

            // Step 3: sing-box config JSON
            val configJson = """
            {
              "log": {
                "level": "warn",
                "timestamp": true
              },
              "dns": {
                "servers": [
                  {
                    "tag": "dns-remote",
                    "address": "8.8.8.8",
                    "detour": "bridge-out"
                  },
                  {
                    "tag": "dns-remote-fallback",
                    "address": "1.1.1.1",
                    "detour": "bridge-out"
                  },
                  {
                    "tag": "dns-direct",
                    "address": "1.1.1.1",
                    "detour": "direct"
                  }
                ],
                "rules": [
                  {
                    "outbound": ["direct"],
                    "server": "dns-direct"
                  },
                  {
                    "outbound": ["any"],
                    "server": "dns-remote"
                  }
                ],
                "strategy": "prefer_ipv4"
              },
              "inbounds": [
                {
                  "type": "tun",
                  "tag": "tun-in",
                  "interface_name": "tun0",
                  "inet4_address": "172.20.0.1/30",
                  "inet6_address": "fdfe:dcba:9876::1/126",
                  "mtu": 1360,
                  "auto_route": true,
                  "strict_route": true,
                  "stack": "gvisor",
                  "sniff": true,
                  "sniff_override_destination": true
                }
              ],
              "outbounds": [
                {
                  "type": "socks",
                  "tag": "bridge-out",
                  "server": "$gatewayIp",
                  "server_port": 10808,
                  "version": "5"
                },
                {
                  "type": "dns",
                  "tag": "dns-out"
                },
                {
                  "type": "direct",
                  "tag": "direct"
                },
                {
                  "type": "block",
                  "tag": "block-out"
                }
              ],
              "route": {
                "auto_detect_interface": true,
                "final": "bridge-out",
                "rules": [
                  {
                    "port": [53],
                    "action": "hijack-dns"
                  },
                  {
                    "port": [853],
                    "outbound": "block-out"
                  },
                  {
                    "ip_cidr": [
                      "10.0.0.0/8",
                      "172.16.0.0/12",
                      "192.168.0.0/16",
                      "127.0.0.0/8",
                      "169.254.0.0/16"
                    ],
                    "outbound": "direct"
                  },
                  {
                    "ip_is_private": true,
                    "outbound": "direct"
                  }
                ]
              }
            }
            """.trimIndent()

            // Step 4: Create platform interface
            platformInterface = BoxPlatformInterface(applicationContext, this)

            // Step 5: Check config validity
            try {
                Libbox.checkConfig(configJson)
            } catch (e: Exception) {
                Log.e(TAG, "Config check failed: ${e.message}")
            }

            // Step 6: Create BoxService
            val service = Libbox.newService(configJson, platformInterface!!)
            boxService = service

            isActive = true
            startTimestamp = System.currentTimeMillis()

            // Step 7: Start service in background thread
            boxThread = Thread({
                try {
                    cancelDisconnectTimeout()
                    acquireLocks()
                    
                    broadcastState(STATE_CONNECTED, null)
                    updateNotification("M-Proxy Köprüsü Aktif")
                    service.start()
                } catch (e: Throwable) {
                    Log.e(TAG, "Box service error: ${e.message}", e)
                    if (isActive) {
                        mainHandler.post {
                            val prefs = getSharedPreferences("mproxy_bridge_prefs", Context.MODE_PRIVATE)
                            val shouldAuto = prefs.getBoolean("should_auto_connect", true)
                            val currentGatewayIp = getGatewayIp()
                            if (shouldAuto) {
                                broadcastState(STATE_CONNECTING, "Motor hatası. Yeniden bağlanılıyor...")
                                stopVpnEngineOnly()
                                
                                releaseLocks()
                                startDisconnectTimeout()
                                
                                if (currentGatewayIp == "192.168.49.1") {
                                    mainHandler.removeCallbacks(retryRunnable)
                                    mainHandler.postDelayed(retryRunnable, 250)
                                }
                            } else {
                                broadcastState(STATE_DISCONNECTED, "Köprü motor hatası: ${e.message}")
                                stopVpn()
                            }
                        }
                    }
                }
            }, "sing-box-bridge").apply {
                isDaemon = true
                start()
            }

            // Step 8: Start connection monitor loop
            mainHandler.postDelayed(checkRunnable, 5000)

        } catch (e: Throwable) {
            Log.e(TAG, "Failed to run VPN engine: ${e.message}", e)
            val prefs = getSharedPreferences("mproxy_bridge_prefs", Context.MODE_PRIVATE)
            val shouldAuto = prefs.getBoolean("should_auto_connect", true)
            if (shouldAuto) {
                val currentGatewayIp = getGatewayIp()
                broadcastState(STATE_CONNECTING, "Bağlantı hatası. Yeniden deneniyor...")
                
                releaseLocks()
                startDisconnectTimeout()
                
                if (currentGatewayIp == "192.168.49.1") {
                    mainHandler.removeCallbacks(retryRunnable)
                    mainHandler.postDelayed(retryRunnable, 250)
                }
            } else {
                broadcastState(STATE_DISCONNECTED, "VPN başlatma hatası: ${e.message}")
                stopVpn()
            }
        }
    }

    private fun checkSocksReachable(ip: String): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, 10808), 2000)
            true
        } catch (e: Exception) {
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun stopVpn() {
        Log.d(TAG, "=== stopVpn called ===")
        isActive = false
        startTimestamp = 0L
        mainHandler.removeCallbacks(checkRunnable)
        mainHandler.removeCallbacks(retryRunnable)
        cancelDisconnectTimeout()

        Thread({
            stopVpnEngineOnly()
            releaseLocks()

            mainHandler.post {
                if (connectionState != STATE_DISCONNECTED) {
                    broadcastState(STATE_DISCONNECTED, null)
                }
                safeStopSelf()
            }
        }, "vpn-stop-thread").start()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "=== onTaskRemoved ===")
        val prefs = getSharedPreferences("mproxy_bridge_prefs", Context.MODE_PRIVATE)
        val shouldAuto = prefs.getBoolean("should_auto_connect", true)
        if (shouldAuto) {
            val restartServiceIntent = Intent(applicationContext, this.javaClass).apply {
                setPackage(packageName)
                action = ACTION_START
            }
            val restartServicePendingIntent = PendingIntent.getService(
                applicationContext, 1, restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmService = applicationContext.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmService.setAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + 1000,
                        restartServicePendingIntent
                    )
                } else {
                    alarmService.set(
                        android.app.AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + 1000,
                        restartServicePendingIntent
                    )
                }
                Log.d(TAG, "Scheduled service restart via AlarmManager in 1s")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule restart alarm: ${e.message}")
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d(TAG, "=== onDestroy ===")
        unregisterNetworkCallback()
        stopVpn()
        super.onDestroy()
    }

    private fun safeStopSelf() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        try { stopSelf() } catch (_: Exception) {}
    }

    private fun broadcastState(state: Int, error: String?) {
        connectionState = state
        isActive = (state == STATE_CONNECTED || state == STATE_CONNECTING)
        if (!isActive) {
            lastIpAddress = null
        }
        try {
            val intent = Intent("com.mproxy.bridge.VPN_STATE").apply {
                setPackage(packageName)
                putExtra("state", state)
                putExtra("active", state == STATE_CONNECTED || state == STATE_CONNECTING)
                putExtra("startTimestamp", startTimestamp)
                putExtra("lastIpAddress", lastIpAddress)
                error?.let { putExtra("error", it) }
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "broadcastState error: ${e.message}")
        }

        // Widget'ı da güncelle
        try {
            MProxyBridgeWidgetProvider.triggerUpdate(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Widget güncelleme hatası: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "M-Proxy Bridge Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "M-Proxy Köprü VPN Servisi Bildirimi"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("M-Proxy Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
        }

    private fun updateNotification(text: String) {
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, createNotification(text))
        } catch (_: Exception) {}
    }

    private fun showDisconnectionNotification() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("M-Proxy Bridge Bağlantısı Kesildi")
                .setContentText("Ana telefon hotspot bağlantısı kapandığı için VPN durduruldu.")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID + 10, builder.build())
        } catch (_: Exception) {}
    }
}
