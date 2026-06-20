package com.mproxy.bridge

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL

/**
 * M-Proxy Bridge Ana Ekran Widget'ı
 * 
 * - Esnek boyutlandırma: her boyuta uyum sağlar
 * - Sadece IP Adresi ve Versiyon kartlarını gösterir
 * - Tıklandığında MainActivity açılır
 * - Orb bağlantı butonu ile tüneli başlatıp durdurur
 */
class MProxyBridgeWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "MProxyBridgeWidget"
        const val ACTION_WIDGET_UPDATE = "com.mproxy.bridge.WIDGET_UPDATE"
        const val ACTION_BRIDGE_TOGGLE = "com.mproxy.bridge.ACTION_BRIDGE_TOGGLE"

        private const val PREFS_CACHE = "mproxy_bridge_widget_cache"

        /**
         * Servisten veya periyodik tetikleyiciden çağrılır.
         * Broadcast göndererek widget'ı günceller.
         */
        fun triggerUpdate(context: Context) {
            val intent = Intent(context, MProxyBridgeWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_UPDATE
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "triggerUpdate: broadcast gönderildi")
        }

        /**
         * Tüm widget örneklerini günceller.
         */
        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, MProxyBridgeWidgetProvider::class.java)
            val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (widgetIds.isEmpty()) {
                Log.d(TAG, "Hiç widget bulunamadı, güncelleme atlandı")
                return
            }
            Log.d(TAG, "${widgetIds.size} widget bulundu, güncelleniyor...")

            // IP adresi arka planda güncellenebilmesi için thread başlatılır
            Thread({
                val data = fetchWidgetData(context)
                for (widgetId in widgetIds) {
                    val views = buildRemoteViews(context, data)
                    try {
                        appWidgetManager.updateAppWidget(widgetId, views)
                        Log.d(TAG, "Widget #$widgetId güncellendi")
                    } catch (e: Exception) {
                        Log.e(TAG, "Widget #$widgetId güncellenemedi: ${e.message}")
                    }
                }
            }, "bridge-widget-update").start()
        }

        /**
         * Servis durumundan ve önbellekten widget verilerini çeker.
         */
        private fun fetchWidgetData(context: Context): WidgetData {
            val isVpnActive = BridgeVpnService.isActive
            val connectionState = BridgeVpnService.connectionState
            val prefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)

            var ipAddress = "–"
            var versionName = "v1.1.1"

            // Sürüm bilgisini al
            try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                versionName = "v" + pInfo.versionName
            } catch (e: Exception) {
                Log.w(TAG, "Versiyon bilgisi alınamadı: ${e.message}")
            }

            // IP adresi çek
            ipAddress = try {
                if (connectionState == BridgeVpnService.STATE_CONNECTED) {
                    val serviceIp = BridgeVpnService.lastIpAddress
                    if (!serviceIp.isNullOrEmpty()) {
                        serviceIp
                    } else {
                        val cachedIp = prefs.getString("cached_ip", null)
                        if (!cachedIp.isNullOrEmpty() && cachedIp != "–") {
                            cachedIp
                        } else {
                            fetchPublicIpViaProxy(context) ?: "–"
                        }
                    }
                } else {
                    prefs.getString("cached_ip", "–") ?: "–"
                }
            } catch (e: Exception) {
                Log.w(TAG, "IP adresi alınamadı: ${e.message}")
                prefs.getString("cached_ip", "–") ?: "–"
            }

            // IP'yi önbelleğe kaydet
            if (ipAddress != "–") {
                prefs.edit().putString("cached_ip", ipAddress).apply()
            }

            return WidgetData(
                isActive = isVpnActive,
                connectionState = connectionState,
                ipAddress = ipAddress,
                versionName = versionName
            )
        }

        /**
         * Köprü proxy'si (socks5: 10808) üzerinden WAN IP adresi çeker.
         */
        private fun fetchPublicIpViaProxy(context: Context): String? {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcpInfo = wifiManager?.dhcpInfo
            val gatewayIp = if (dhcpInfo != null && dhcpInfo.gateway != 0) {
                val ipInt = dhcpInfo.gateway
                "${ipInt and 0xFF}.${(ipInt shr 8) and 0xFF}.${(ipInt shr 16) and 0xFF}.${(ipInt shr 24) and 0xFF}"
            } else {
                "192.168.49.1"
            }

            val proxy = java.net.Proxy(
                java.net.Proxy.Type.SOCKS,
                InetSocketAddress(gatewayIp, 10808)
            )

            val providers = listOf("https://api.ipify.org", "https://icanhazip.com")
            for (urlStr in providers) {
                try {
                    val conn = URL(urlStr).openConnection(proxy) as HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    conn.connect()
                    if (conn.responseCode == 200) {
                        val ip = conn.inputStream.bufferedReader().readText().trim()
                        conn.disconnect()
                        if (ip.isNotEmpty() && ip.contains('.')) return ip
                    }
                } catch (_: Exception) {}
            }
            return null
        }

        /**
         * Widget görünümünü oluşturur.
         */
        private fun buildRemoteViews(context: Context, data: WidgetData): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_bridge)

            // ── Tıklama (Genel alan) → Uygulamayı Aç ──
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 10, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            // ── Tıklama (Orb) → Bağlantıyı Başlat/Durdur ──
            val toggleIntent = Intent(context, MProxyBridgeWidgetProvider::class.java).apply {
                action = ACTION_BRIDGE_TOGGLE
            }
            val togglePendingIntent = PendingIntent.getBroadcast(
                context, 11, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_orb_click_area, togglePendingIntent)

            // ── Kart değerleri ──
            views.setTextViewText(R.id.widget_ip_value, data.ipAddress)
            views.setTextViewText(R.id.widget_version_value, data.versionName)

            // ── BAĞLANTI DURUMU (Orb Tasarımı) ──
            when (data.connectionState) {
                BridgeVpnService.STATE_CONNECTED -> {
                    // Aktif: Parlayan cyan çerçeve ve cyan ikon/yazı
                    views.setInt(R.id.widget_orb_border, "setColorFilter", 0xFF00E5FF.toInt())
                    views.setInt(R.id.widget_status_icon, "setColorFilter", 0xFF00E5FF.toInt())
                    views.setTextViewText(R.id.widget_status_text, "AKTİF")
                    views.setTextColor(R.id.widget_status_text, 0xFF00E5FF.toInt())
                }
                BridgeVpnService.STATE_CONNECTING -> {
                    // Bağlanıyor: Parlayan sarı/turuncu çerçeve ve ikon
                    views.setInt(R.id.widget_orb_border, "setColorFilter", 0xFFFBC531.toInt())
                    views.setInt(R.id.widget_status_icon, "setColorFilter", 0xFFFBC531.toInt())
                    views.setTextViewText(R.id.widget_status_text, "BAĞLANIYOR")
                    views.setTextColor(R.id.widget_status_text, 0xFFFBC531.toInt())
                }
                else -> {
                    // Pasif: Yarı şeffaf beyaz çerçeve ve ikon
                    views.setInt(R.id.widget_orb_border, "setColorFilter", 0x80FFFFFF.toInt())
                    views.setInt(R.id.widget_status_icon, "setColorFilter", 0x80FFFFFF.toInt())
                    views.setTextViewText(R.id.widget_status_text, "PASİF")
                    views.setTextColor(R.id.widget_status_text, 0x80FFFFFF.toInt())
                }
            }

            return views
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Log.d(TAG, "onUpdate tetiklendi")
        updateAllWidgets(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Log.d(TAG, "onReceive action: ${intent.action}")

        if (intent.action == ACTION_WIDGET_UPDATE || intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            updateAllWidgets(context)
        } else if (intent.action == ACTION_BRIDGE_TOGGLE) {
            val isActive = BridgeVpnService.isActive
            val connectionState = BridgeVpnService.connectionState

            val bridgePrefs = context.getSharedPreferences("mproxy_bridge_prefs", Context.MODE_PRIVATE)

            if (isActive || connectionState == BridgeVpnService.STATE_CONNECTING) {
                // Bağlantıyı Durdur
                bridgePrefs.edit().putBoolean("should_auto_connect", false).apply()
                val stopIntent = Intent(context, BridgeVpnService::class.java).apply {
                    action = BridgeVpnService.ACTION_STOP
                }
                try {
                    context.startService(stopIntent)
                    android.widget.Toast.makeText(context, "Bağlantı durduruluyor...", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Servis durdurulamadı: ${e.message}")
                }
            } else {
                // Bağlantıyı Başlat
                bridgePrefs.edit().putBoolean("should_auto_connect", true).apply()
                
                // VpnService.prepare kontrolü (eğer izin verilmemişse önce uygulamayı açmalı)
                val vpnPreparedIntent = android.net.VpnService.prepare(context)
                if (vpnPreparedIntent != null) {
                    // İzin eksik: Uygulamaya yönlendir
                    android.widget.Toast.makeText(context, "Lütfen önce uygulamayı açıp VPN izni verin.", android.widget.Toast.LENGTH_LONG).show()
                    val appIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(appIntent)
                } else {
                    // İzin var: Servisi başlat
                    val startIntent = Intent(context, BridgeVpnService::class.java).apply {
                        action = BridgeVpnService.ACTION_START
                    }
                    try {
                        androidx.core.content.ContextCompat.startForegroundService(context, startIntent)
                        android.widget.Toast.makeText(context, "Bağlantı kuruluyor...", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Servis başlatılamadı: ${e.message}")
                    }
                }
            }

            // Arayüzü hızlıca güncelle
            updateAllWidgets(context)
        }
    }

    override fun onEnabled(context: Context) {
        Log.d(TAG, "onEnabled: widget eklendi")
        updateAllWidgets(context)
    }

    /** Widget veri modeli */
    data class WidgetData(
        val isActive: Boolean,
        val connectionState: Int,
        val ipAddress: String,
        val versionName: String
    )
}
