package com.cleartether.app.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException

/** Opens outbound connections on behalf of tethered clients. */
fun interface UpstreamConnector {
    suspend fun connect(host: String, port: Int): AsyncSocket
}

/** Destination rejected by [AddressPolicy]. */
class BlockedDestinationException(message: String) : IOException(message)

/** All candidate addresses timed out. */
class UpstreamTimeoutException(message: String) : IOException(message)

/**
 * Resolves [host] on the phone's own (cellular) network and connects with a simplified
 * "Happy Eyeballs": each address gets a short attempt window and the last one gets the rest of
 * the budget, so a dead IPv6 path never stalls a page load for the full timeout.
 */
class DirectUpstreamConnector(
    private val policy: AddressPolicy,
    private val totalTimeoutMs: Long = 15_000,
    private val perAddressTimeoutMs: Long = 4_000,
    private val resolver: suspend (String) -> List<InetAddress> = { name ->
        withContext(Dispatchers.IO) { InetAddress.getAllByName(name).toList() }
    },
) : UpstreamConnector {

    override suspend fun connect(host: String, port: Int): AsyncSocket {
        require(port in 1..65535) { "Invalid port $port" }
        val literal = InetAddresses.parseLiteral(host)
        val candidates = if (literal != null) listOf(literal) else try {
            resolver(host)
        } catch (e: UnknownHostException) {
            throw e
        } catch (e: Exception) {
            throw UnknownHostException("$host: ${e.message}")
        }
        val allowed = candidates.filter(policy::isDestinationAllowed)
        if (allowed.isEmpty()) {
            throw if (candidates.isEmpty()) UnknownHostException(host)
            else BlockedDestinationException("Destination $host is not reachable through the tether")
        }

        val ordered = interleaveFamilies(allowed).take(MAX_ATTEMPTS)
        val deadline = System.nanoTime() + totalTimeoutMs * 1_000_000
        var lastError: Exception? = null
        for ((index, address) in ordered.withIndex()) {
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) break
            val budget = if (index == ordered.lastIndex) remaining else minOf(perAddressTimeoutMs, remaining)
            try {
                return withTimeout(budget) { AsyncSocket.connect(InetSocketAddress(address, port)) }
            } catch (e: TimeoutCancellationException) {
                lastError = UpstreamTimeoutException("Timed out connecting to $host")
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: UpstreamTimeoutException("Timed out connecting to $host")
    }

    private fun interleaveFamilies(addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.size < 2) return addresses
        val first = addresses.first().javaClass
        val primary = addresses.filter { it.javaClass == first }.toMutableList()
        val secondary = addresses.filter { it.javaClass != first }.toMutableList()
        val out = ArrayList<InetAddress>(addresses.size)
        while (primary.isNotEmpty() || secondary.isNotEmpty()) {
            if (primary.isNotEmpty()) out += primary.removeAt(0)
            if (secondary.isNotEmpty()) out += secondary.removeAt(0)
        }
        return out
    }

    private companion object {
        const val MAX_ATTEMPTS = 4
    }
}

/** Maps a connection failure to a short human readable reason. */
fun Throwable.describeUpstreamFailure(): String = when (this) {
    is BlockedDestinationException -> "Blocked destination"
    is UnknownHostException -> "Host not found"
    is UpstreamTimeoutException -> "Connection timed out"
    is ConnectException -> "Connection refused"
    else -> message ?: javaClass.simpleName
}
