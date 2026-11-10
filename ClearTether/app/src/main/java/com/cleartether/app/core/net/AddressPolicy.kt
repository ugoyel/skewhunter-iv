package com.cleartether.app.core.net

import java.net.InetAddress

/**
 * Security policy for the tethering proxy.
 *
 * - **Client ACL**: only devices inside the Wi-Fi Direct group subnet may use the proxy. That
 *   blocks the open-proxy abuse that would happen if a carrier handed the phone a publicly
 *   reachable address.
 * - **Destination ACL**: clients must not reach the host phone's own loopback services (ADB,
 *   other apps' local servers), link-local or multicast space, or the tether subnet itself.
 *   Checks run after DNS resolution, so they also defeat DNS-rebinding tricks such as
 *   `localtest.me`.
 */
class AddressPolicy(
    private val allowedClientNetworks: List<Cidr> = listOf(Cidr.parse(DEFAULT_GROUP_SUBNET)),
    private val allowLoopbackClients: Boolean = false,
    private val blockedDestinationNetworks: List<Cidr> = listOf(Cidr.parse(DEFAULT_GROUP_SUBNET)),
    private val allowLoopbackDestinations: Boolean = false,
) {
    fun isClientAllowed(address: InetAddress?): Boolean {
        if (address == null) return false
        if (address.isLoopbackAddress) return allowLoopbackClients
        return allowedClientNetworks.any { it.contains(address) }
    }

    fun isDestinationAllowed(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return allowLoopbackDestinations
        if (address.isAnyLocalAddress || address.isLinkLocalAddress || address.isMulticastAddress) return false
        return blockedDestinationNetworks.none { it.contains(address) }
    }

    companion object {
        /** Android always assigns 192.168.49.0/24 to the Wi-Fi Direct group owner. */
        const val DEFAULT_GROUP_SUBNET = "192.168.49.0/24"
        const val DEFAULT_GROUP_OWNER_IP = "192.168.49.1"
    }
}
