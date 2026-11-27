package app.vitranslator.phone

import app.vitranslator.phone.xbox.XboxHome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XboxTest {
    @Test
    fun teredoAddressGivesItsIpv4Addresses() {
        // (RFC 4380's example, as on Wikipedia's Teredo page)
        val t = XboxHome.teredo("2001:0000:4136:e378:8000:63bf:3fff:fdd2")
        assertEquals(Triple("65.54.227.120", "192.0.2.45", 40000), t)
        assertEquals(Triple("65.54.227.120", "192.0.2.45", 40000), XboxHome.teredo("2001:0:4136:e378:8000:63bf:3fff:fdd2"))
    }

    @Test
    fun otherAddressesAreNotTeredo() {
        assertNull(XboxHome.teredo("192.168.1.20"))
        assertNull(XboxHome.teredo("fe80::1"))
        assertNull(XboxHome.teredo("2001:db8::1"))
    }

    @Test
    fun teredoCandidateAddsHostCandidates() {
        val src = Triple("a=candidate:1 1 UDP 1 2001:0:4136:e378:8000:63bf:3fff:fdd2 9002 typ host", "0", 0)
        val out = XboxHome.withTeredo(listOf(src, Triple("a=candidate:2 1 UDP 1 192.168.1.20 9002 typ host", "0", 0)))
        assertEquals(src, out[0])
        val added = out.subList(1, 6).map { it.first.split(' ').let { p -> p[4] + ":" + p[5] } }
        assertEquals(listOf("192.0.2.45:40000", "65.54.227.120:9002", "192.0.2.45:9002", "65.54.227.120:3074", "192.0.2.45:3074"), added)
        assertEquals(7, out.size)
    }
}
