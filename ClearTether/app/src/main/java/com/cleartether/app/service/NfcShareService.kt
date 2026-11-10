package com.cleartether.app.service

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.cleartether.app.AppGraph
import com.cleartether.app.core.share.Type4TagEmulator

/**
 * "Tap to connect" over NFC: while the host is sharing, the phone emulates an NFC Forum Type 4
 * tag (Host Card Emulation) carrying:
 *  1. a Wi-Fi Simple Config credential: other Android phones show "Connect to network?" on tap
 *  2. the setup-page URL
 *  3. an Android Application Record: ClearTether Client opens directly (or Play Store installs it)
 *
 * ClearTether Client in reader mode parses the credential itself and connects in one step.
 * The tag answers only while tethering runs with NFC sharing enabled, and (per apduservice.xml)
 * only while the host is unlocked.
 */
class NfcShareService : HostApduService() {
    private val tag by lazy { Type4TagEmulator { AppGraph.from(this).runtime.nfcPayload.value } }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray = tag.process(commandApdu)

    override fun onDeactivated(reason: Int) = tag.reset()
}
