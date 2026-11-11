package io.github.ugoyel.hearby

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Small helpers for working on the local Wi-Fi / hotspot network. */
object LocalNet {

    /** The Wi-Fi network this phone has joined as a client, if any (not its own hotspot). */
    fun wifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network)
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    /**
     * Pin a listener's socket to Wi-Fi.
     *
     * A hotspot (or any Wi-Fi without internet) is usually *not* Android's default network: mobile
     * data stays the default and unbound sockets would send LAN packets out over cellular, where
     * they vanish. Binding the socket to the Wi-Fi network fixes that, and also side-steps VPNs.
     */
    fun bindToWifi(context: Context, socket: DatagramSocket): Boolean {
        val wifi = wifiNetwork(context) ?: return false
        return try {
            wifi.bindSocket(socket)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Host side. Left unbound, a socket reaches hotspot clients (Android routes its own tethering
     * subnet for every app) and the default network. Only when this phone sits on a Wi-Fi that is
     * not the default network (Wi-Fi without internet while mobile data is on) do we have to pin it.
     */
    fun bindHostSocketIfNeeded(context: Context, socket: DatagramSocket) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val wifi = wifiNetwork(context) ?: return
        if (wifi != cm.activeNetwork) {
            try {
                wifi.bindSocket(socket)
            } catch (e: Exception) {
                // keep the unbound socket
            }
        }
    }

    /** Site-local IPv4 addresses of this device, for "enter the host address manually". */
    fun localIpv4Addresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Where to send discovery probes: the limited and subnet broadcast addresses, plus the Wi-Fi
     * gateway and DHCP server. On a phone hotspot those last two *are* the host, so discovery works
     * there even if broadcast and mDNS are both filtered.
     */
    fun probeTargets(context: Context): Set<InetAddress> {
        val targets = LinkedHashSet<InetAddress>()
        try {
            targets.add(InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1)))
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) ia.broadcast?.let { targets.add(it) }
            }
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val link = wifiNetwork(context)?.let { cm?.getLinkProperties(it) }
            if (link != null) {
                for (route in link.routes) {
                    val gw = route.gateway
                    if (route.isDefaultRoute && gw is Inet4Address && !gw.isAnyLocalAddress) targets.add(gw)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) link.dhcpServerAddress?.let { targets.add(it) }
            }
        } catch (e: Exception) {
            // best effort
        }
        return targets
    }

    /** The user-visible device name ("Mia's Pixel"), falling back to the model. */
    fun deviceName(context: Context): String {
        val custom = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (e: Exception) {
            null
        }
        return custom?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android"
    }
}
