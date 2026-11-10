package com.cleartether.app.service

import android.net.DnsResolver
import android.os.CancellationSignal
import com.cleartether.app.core.proxy.DnsBackend
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Resolves raw DNS messages through the host phone's own resolver (`DnsResolver.rawQuery`).
 * Honours Private DNS (DNS-over-TLS) settings, uses the cellular network, and shares the
 * system DNS cache.
 */
class AndroidDnsBackend : DnsBackend {
    private val direct = Executor { it.run() }

    @Suppress("DEPRECATION") // getInstance() is the only entry point on API 29-36
    override suspend fun query(message: ByteArray): ByteArray = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        DnsResolver.getInstance().rawQuery(
            null, message, DnsResolver.FLAG_EMPTY, direct, signal,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) = cont.resume(answer)
                override fun onError(error: DnsResolver.DnsException) = cont.resumeWithException(error)
            },
        )
    }
}
