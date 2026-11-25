package com.v2ray.ang.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SlowDnsQnameStepsTest {

    @Test
    fun stepsForShortDomainEndAtFloor() {
        // Mode native (2.4.25): "dns.nisavpn.biz.id" = 18 karakter, batas terkecil 18 + 45 = 63.
        assertEquals(listOf(0, 130, 100, 63), SlowDnsManager.qnameSteps("dns.nisavpn.biz.id"))
    }

    @Test
    fun stepsDropValuesBelowFloorForLongDomain() {
        assertEquals(listOf(0, 130, 100, 85), SlowDnsManager.qnameSteps("x".repeat(40)))
        assertEquals(listOf(0, 130, 115), SlowDnsManager.qnameSteps("x".repeat(70)))
    }

    @Test
    fun stepsForTooLongDomainOnlyDefault() {
        assertEquals(listOf(0), SlowDnsManager.qnameSteps("x".repeat(210)))
    }

    @Test
    fun stepsForCompatModeUseHigherFloor() {
        // Mode -dnstt-compat lama: overhead lebih besar, lantai domain + 64.
        assertEquals(listOf(0, 130, 100, 82), SlowDnsManager.qnameSteps("dns.nisavpn.biz.id", compat = true))
    }

    @Test
    fun nextQnameWalksDownAndStops() {
        val steps = SlowDnsManager.qnameSteps("dns.nisavpn.biz.id")
        assertEquals(130, SlowDnsManager.nextQname(steps, 0, 0))
        assertEquals(100, SlowDnsManager.nextQname(steps, 130, 0))
        assertEquals(63, SlowDnsManager.nextQname(steps, 100, 0))
        assertNull(SlowDnsManager.nextQname(steps, 63, 0))
    }

    @Test
    fun nextQnameSkipsRejectedValues() {
        val steps = SlowDnsManager.qnameSteps("dns.nisavpn.biz.id")
        // Klien menolak 63 (MTU di bawah minimum): dari 100 tidak ada langkah lagi.
        assertNull(SlowDnsManager.nextQname(steps, 100, 63))
    }
}
