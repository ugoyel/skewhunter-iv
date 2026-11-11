package io.github.ugoyel.hearby.ui

import io.github.ugoyel.hearby.core.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressParsingTest {
    @Test
    fun acceptsLiteralIpv4WithOptionalPort() {
        assertEquals("192.168.43.1" to Proto.PORT, parseAddress("192.168.43.1"))
        assertEquals("10.0.0.7" to 5000, parseAddress(" 10.0.0.7:5000 "))
        assertEquals("192.168.1.20" to Proto.PORT, parseAddress("192.168.001.020"))
    }

    @Test
    fun rejectsEverythingElse() {
        // Host names are refused on purpose: resolving one would mean a DNS lookup on the main thread.
        assertNull(parseAddress("my-phone.local"))
        assertNull(parseAddress("192.168.1"))
        assertNull(parseAddress("192.168.1.256"))
        assertNull(parseAddress("192.168.1.2:0"))
        assertNull(parseAddress("192.168.1.2:70000"))
        assertNull(parseAddress(""))
    }
}
