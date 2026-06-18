package com.mproxy.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Receives BOOT_COMPLETED and starts BridgeVpnService so it can register its
 * ConnectivityManager.NetworkCallback immediately — even if the app has never
 * been opened by the user.
 *
 * Wi-Fi / hotspot network changes are handled entirely inside BridgeVpnService
 * via ConnectivityManager.NetworkCallback (TRANSPORT_WIFI), which stays alive
 * as long as the service is alive (START_STICKY + onTaskRemoved restart).
 *
 * We intentionally do NOT register for NETWORK_STATE_CHANGED_ACTION or
 * android.net.conn.CONNECTIVITY_CHANGE here because:
 *  - CONNECTIVITY_CHANGE is restricted on Android 7+ (API 24) for manifest receivers.
 *  - NETWORK_STATE_CHANGED_ACTION is deprecated and unreliable on API 26+.
 * The NetworkCallback inside BridgeVpnService covers all network events.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "BOOT_COMPLETED received — checking should_auto_connect flag")

        val prefs = context.getSharedPreferences("mproxy_bridge_prefs", Context.MODE_PRIVATE)
        val shouldAuto = prefs.getBoolean("should_auto_connect", true)

        if (!shouldAuto) {
            Log.d(TAG, "Auto-connect is disabled, not starting service on boot.")
            return
        }

        // Start the service in the foreground so Android doesn't kill it immediately.
        // The service will register its NetworkCallback in onCreate() and will start
        // the VPN tunnel once it detects the target hotspot via onAvailable().
        val serviceIntent = Intent(context, BridgeVpnService::class.java).apply {
            action = BridgeVpnService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "BridgeVpnService started on boot. NetworkCallback will handle Wi-Fi events.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service on boot: ${e.message}")
        }
    }
}
