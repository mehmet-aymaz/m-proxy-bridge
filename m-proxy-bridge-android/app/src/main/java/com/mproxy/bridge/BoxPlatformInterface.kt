package com.mproxy.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.LinkProperties
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import libbox.InterfaceUpdateListener
import libbox.NetworkInterface
import libbox.NetworkInterfaceIterator
import libbox.Notification
import libbox.PlatformInterface
import libbox.StringIterator
import libbox.WIFIState
import libbox.TunOptions

class BoxPlatformInterface(
    private val context: Context,
    private val vpnService: VpnService
) : PlatformInterface {

    private var tunFd: ParcelFileDescriptor? = null
    private var defaultInterfaceListener: InterfaceUpdateListener? = null

    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            updateDefaultInterface(linkProperties)
        }
    }

    @SuppressLint("MissingPermission")
    override fun openTun(options: TunOptions): Int {
        Log.d(TAG, "=== openTun called ===")
        Log.d(TAG, "openTun options: MTU=${options.getMTU()}, autoRoute=${options.getAutoRoute()}, strictRoute=${options.getStrictRoute()}")

        val builder = vpnService.Builder()
        builder.setSession("M-Proxy Bridge VPN")

        // Cap MTU to 1500 (large values like 9000 can cause establish() to return null on some devices)
        val rawMtu = options.getMTU()
        val mtu = if (rawMtu <= 0 || rawMtu > 1500) 1500 else rawMtu
        Log.d(TAG, "Setting MTU: $mtu (raw value was $rawMtu)")
        builder.setMtu(mtu)

        // Set address from options, validating that at least one address is added
        var addedIPv4 = false
        try {
            val inet4Address = options.getInet4Address()
            while (inet4Address.hasNext()) {
                val prefix = inet4Address.next()
                Log.d(TAG, "Adding IPv4 address from options: ${prefix.address()}/${prefix.prefix()}")
                builder.addAddress(prefix.address(), prefix.prefix())
                addedIPv4 = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read IPv4 address from options: ${e.message}")
        }

        if (!addedIPv4) {
            Log.w(TAG, "No IPv4 address added from options, adding default 172.20.0.1/30")
            try {
                builder.addAddress("172.20.0.1", 30)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add default IPv4 address: ${e.message}")
            }
        }

        // Set IPv6 address from options dynamically
        var addedIPv6 = false
        try {
            val inet6Address = options.getInet6Address()
            while (inet6Address.hasNext()) {
                val prefix = inet6Address.next()
                try {
                    Log.d(TAG, "Adding IPv6 address from options: ${prefix.address()}/${prefix.prefix()}")
                    builder.addAddress(prefix.address(), prefix.prefix())
                    addedIPv6 = true
                } catch (ea: Exception) {
                    Log.w(TAG, "Failed to add options IPv6 address: ${ea.message}")
                }
            }
            if (!addedIPv6) {
                try {
                    Log.d(TAG, "Adding default IPv6 address: fdfe:dcba:9876::1/126")
                    builder.addAddress("fdfe:dcba:9876::1", 126)
                    addedIPv6 = true
                } catch (ex: Exception) {
                    Log.w(TAG, "Failed to add default IPv6 address: ${ex.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No inet6 address config or error: ${e.message}")
        }

        // Add DNS servers
        builder.addDnsServer("1.1.1.1")
        builder.addDnsServer("8.8.8.8")
        if (addedIPv6) {
            try {
                builder.addDnsServer("2606:4700:4700::1111")
                builder.addDnsServer("2001:4860:4860::8888")
                Log.d(TAG, "IPv6 DNS servers added successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add IPv6 DNS: ${e.message}")
            }
        }

        // Route all traffic through VPN
        builder.addRoute("0.0.0.0", 0)
        if (addedIPv6) {
            try {
                builder.addRoute("::", 0)
                Log.d(TAG, "IPv6 route ::/0 added successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add IPv6 route ::/0: ${e.message}")
            }
        }

        // Exclude packages
        try {
            val excludePackages = options.getExcludePackage()
            var excludedSelf = false
            while (excludePackages.hasNext()) {
                val pkg = excludePackages.next()
                if (pkg == context.packageName) {
                    excludedSelf = true
                }
                try { builder.addDisallowedApplication(pkg) } catch (_: Exception) {}
            }
            if (!excludedSelf) {
                try { builder.addDisallowedApplication(context.packageName) } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            try { builder.addDisallowedApplication(context.packageName) } catch (_: Exception) {}
        }

        // Strict route
        if (options.getStrictRoute()) {
            builder.setBlocking(true)
        }

        val pfd = builder.establish()
        if (pfd == null) {
            Log.e(TAG, "openTun: establish() returned NULL - VPN permission issue?")
            throw Exception("VPN permission not granted or TUN creation failed")
        }
        tunFd = pfd

        Log.d(TAG, "openTun: TUN created OK, fd=${pfd.fd}")
        return pfd.fd
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        try {
            vpnService.protect(fd)
        } catch (e: Exception) {
            Log.w(TAG, "autoDetectInterfaceControl error: ${e.message}")
        }
    }

    override fun clearDNSCache() {}

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        Log.d(TAG, "=== closeDefaultInterfaceMonitor ===")
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        defaultInterfaceListener = null
    }

    override fun findConnectionOwner(
        protocol: Int, sourceAddress: String, sourcePort: Int,
        destinationAddress: String, destinationPort: Int
    ): Int = -1

    override fun getInterfaces(): NetworkInterfaceIterator {
        val interfaces = mutableListOf<NetworkInterface>()
        try {
            val enumeration = java.net.NetworkInterface.getNetworkInterfaces()
            while (enumeration.hasMoreElements()) {
                val netint = enumeration.nextElement()
                val name = netint.name.lowercase()
                if (netint.isUp && !netint.isLoopback) {
                    if (name.contains("tun") || name.contains("tap") || name.contains("lo") || name.contains("dummy")) {
                        continue
                    }
                    val nif = NetworkInterface()
                    nif.setName(netint.name)
                    nif.setIndex(netint.index)
                    
                    var flags = 1
                    if (netint.isPointToPoint) flags = flags or 8
                    if (netint.supportsMulticast()) flags = flags or 16
                    nif.setFlags(flags)

                    try {
                        nif.setMTU(netint.mtu)
                    } catch (_: Exception) {
                        nif.setMTU(1500)
                    }

                    val addrs = mutableListOf<String>()
                    for (addr in netint.interfaceAddresses) {
                        val ip = addr.address.hostAddress ?: continue
                        val cleanIp = ip.substringBefore("%")
                        addrs.add("$cleanIp/${addr.networkPrefixLength}")
                    }
                    nif.setAddresses(createStringIterator(addrs))
                    nif.setDNSServer(createStringIterator(emptyList()))

                    interfaces.add(nif)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error listing interfaces: ${e.message}")
        }
        if (interfaces.isEmpty()) {
            val nif = NetworkInterface()
            nif.setName("lo")
            nif.setIndex(1)
            nif.setFlags(1)
            nif.setMTU(1500)
            nif.setAddresses(createStringIterator(listOf("127.0.0.1/8")))
            nif.setDNSServer(createStringIterator(emptyList()))
            interfaces.add(nif)
        }
        return object : NetworkInterfaceIterator {
            private var index = 0
            override fun hasNext(): Boolean = index < interfaces.size
            override fun next(): NetworkInterface = interfaces[index++]
        }
    }

    private fun createStringIterator(list: List<String>): StringIterator {
        return object : StringIterator {
            private var index = 0
            override fun hasNext(): Boolean = index < list.size
            override fun next(): String = if (hasNext()) list[index++] else ""
            override fun len(): Int = list.size
        }
    }

    override fun includeAllNetworks(): Boolean = false

    override fun packageNameByUid(uid: Int): String {
        return try {
            context.packageManager.getNameForUid(uid) ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    override fun readWIFIState(): WIFIState {
        return WIFIState("", "")
    }

    override fun sendNotification(notification: Notification?) {}

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        Log.d(TAG, "=== startDefaultInterfaceMonitor ===")
        defaultInterfaceListener = listener
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork != null) {
                val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
                if (linkProperties != null) {
                    updateDefaultInterface(linkProperties)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start default interface monitor: ${e.message}")
            notifyFallbackDefaultInterface()
        }
    }

    private fun updateDefaultInterface(linkProperties: LinkProperties) {
        val name = linkProperties.interfaceName ?: return
        var index = 0
        try {
            val netint = java.net.NetworkInterface.getByName(name)
            if (netint != null) {
                index = netint.index
            }
        } catch (_: Exception) {}

        var hasIPv4 = false
        var hasIPv6 = false
        try {
            val netint = java.net.NetworkInterface.getByName(name)
            if (netint != null) {
                val addrs = netint.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress) {
                        if (addr is java.net.Inet4Address) {
                            hasIPv4 = true
                        } else if (addr is java.net.Inet6Address) {
                            hasIPv6 = true
                        }
                    }
                }
            }
        } catch (_: Exception) {
            hasIPv4 = true
        }

        try {
            defaultInterfaceListener?.updateDefaultInterface(name, index, hasIPv4, hasIPv6)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating default interface: ${e.message}")
        }
    }

    private fun notifyFallbackDefaultInterface() {
        try {
            val enumeration = java.net.NetworkInterface.getNetworkInterfaces()
            while (enumeration.hasMoreElements()) {
                val netint = enumeration.nextElement()
                val name = netint.name.lowercase()
                if (netint.isUp && !netint.isLoopback) {
                    if (name.contains("tun") || name.contains("tap") || name.contains("lo") || name.contains("dummy")) {
                        continue
                    }
                    defaultInterfaceListener?.updateDefaultInterface(netint.name, netint.index, true, false)
                    return
                }
            }
        } catch (_: Exception) {}
    }

    override fun uidByPackageName(packageName: String): Int {
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            info.uid
        } catch (_: Exception) { -1 }
    }

    override fun underNetworkExtension(): Boolean = false

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun useProcFS(): Boolean = false

    override fun writeLog(message: String?) {
        Log.d(TAG, "libbox: $message")
    }

    fun closeTun() {
        Log.d(TAG, "closeTun called, tunFd=$tunFd")
        try { 
            tunFd?.close() 
            Log.d(TAG, "tunFd closed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing tunFd: ${e.message}", e)
        }
        tunFd = null
    }

    companion object {
        private const val TAG = "BoxPlatformBridge"
    }
}
