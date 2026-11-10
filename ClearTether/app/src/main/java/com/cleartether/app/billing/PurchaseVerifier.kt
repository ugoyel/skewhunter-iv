package com.cleartether.app.billing

import android.util.Base64
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Verifies Google Play purchase signatures with the app's Play Console license key
 * (Monetize > Monetization setup). Configure it as the `CLEARTETHER_PLAY_LICENSE_KEY` Gradle
 * property. For high-value fraud resistance, also verify tokens server-side with the Play
 * Developer API; this check stops casual tampering and replayed fake purchases.
 */
class PurchaseVerifier(base64PublicKey: String) {
    private val key: PublicKey? = base64PublicKey.takeIf { it.isNotBlank() }?.let {
        runCatching {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(it, Base64.DEFAULT)))
        }.getOrNull()
    }

    val isConfigured: Boolean get() = key != null

    fun verify(signedData: String, signature: String): Boolean {
        val k = key ?: return true // not configured (debug/dev builds): trust Play's response
        if (signature.isBlank()) return false
        return runCatching {
            Signature.getInstance("SHA1withRSA").run {
                initVerify(k)
                update(signedData.toByteArray(Charsets.UTF_8))
                verify(Base64.decode(signature, Base64.DEFAULT))
            }
        }.getOrDefault(false)
    }
}
