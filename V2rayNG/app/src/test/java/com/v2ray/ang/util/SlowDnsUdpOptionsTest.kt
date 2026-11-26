package com.v2ray.ang.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SlowDnsUdpOptionsTest {
    private fun p(raw: String) = SlowDnsUdpOptions.parse(raw, "2s")

    @Test
    fun withoutOptionsFlagsAreUnchanged() {
        val o = p("1.1.1.1")
        assertEquals(listOf("-udp-timeout", "2s"), o.flags)
        assertNull(o.qname)
        assertTrue(o.warnings.isEmpty())
    }

    @Test
    fun existingKeysStillWork() {
        val o = p("1.1.1.1|rps=40,workers=20,timeout=1500ms")
        assertEquals(listOf("-udp-timeout", "1500ms", "-udp-workers", "20", "-rps", "40"), o.flags)
    }

    @Test
    fun sharedStillWorks() {
        assertTrue(p("1.1.1.1|shared=1").flags.contains("-udp-shared-socket"))
    }

    @Test
    fun qnameIsParsedAndClamped() {
        assertEquals(120, p("1.1.1.1|qname=120").qname)
        assertEquals(60, p("1.1.1.1|qname=40").qname)
        assertEquals(253, p("1.1.1.1|qname=999").qname)
        assertNull(p("1.1.1.1|qname=abc").qname)
    }

    @Test
    fun queueAndWindowAreClamped() {
        assertTrue(p("1.1.1.1|queue=8").flags.containsAll(listOf("-queue-size", "128")))
        assertTrue(p("1.1.1.1|queue=99999").flags.containsAll(listOf("-queue-size", "4096")))
        assertTrue(p("1.1.1.1|window=1").flags.containsAll(listOf("-kcp-window-size", "32")))
    }

    @Test
    fun windowAboveQueueRaisesQueue() {
        val o = p("1.1.1.1|queue=128,window=512")
        assertTrue(o.flags.containsAll(listOf("-queue-size", "1024", "-kcp-window-size", "512")))
        assertTrue(o.warnings.any { it.contains("window") })
    }

    @Test
    fun windowAboveDefaultQueueRaisesQueue() {
        assertTrue(p("1.1.1.1|window=1024").flags.containsAll(listOf("-queue-size", "2048")))
    }

    @Test
    fun windowWithinDefaultQueueAddsNoQueueFlag() {
        assertTrue(!p("1.1.1.1|window=256").flags.contains("-queue-size"))
    }

    @Test
    fun openstreamAcceptsSecondsAndMillis() {
        assertTrue(p("1.1.1.1|openstream=20s").flags.containsAll(listOf("-open-stream-timeout", "20000ms")))
        assertTrue(p("1.1.1.1|openstream=20000ms").flags.containsAll(listOf("-open-stream-timeout", "20000ms")))
        assertTrue(p("1.1.1.1|openstream=1s").flags.containsAll(listOf("-open-stream-timeout", "5000ms")))
        assertTrue(p("1.1.1.1|openstream=90s").flags.containsAll(listOf("-open-stream-timeout", "60000ms")))
    }

    @Test
    fun idleIsClamped() {
        assertTrue(p("1.1.1.1|idle=5s").flags.containsAll(listOf("-idle-timeout", "15000ms")))
        assertTrue(p("1.1.1.1|idle=300s").flags.containsAll(listOf("-idle-timeout", "120000ms")))
    }

    @Test
    fun summaryContainsEffectiveValues() {
        val s = p("1.1.1.1|queue=128,window=512").summary
        assertTrue(s.contains("queue=1024") && s.contains("window=512"))
    }
}
