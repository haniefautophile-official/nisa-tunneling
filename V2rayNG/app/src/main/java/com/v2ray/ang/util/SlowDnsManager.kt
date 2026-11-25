package com.v2ray.ang.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SlowDnsQnameStepsTest {

    @Test
    fun nativeFloorMatchesMeasuredValues() {
        // Terukur di server: domain 18 karakter, clientid 2 -> lantai 65; clientid 1 -> 64.
        assertEquals(65, SlowDnsManager.qnameFloor("dns.nisavpn.biz.id", compat = false, clientIdSize = 2))
        assertEquals(64, SlowDnsManager.qnameFloor("dns.nisavpn.biz.id", compat = false, clientIdSize = 1))
    }

    @Test
    fun nativeStepsForShortDomainEndAtFloor() {
        assertEquals(listOf(0, 80, 70, 65), SlowDnsManager.qnameSteps("dns.nisavpn.biz.id", compat = false, clientIdSize = 2))
    }

    @Test
    fun nativeStepsDropValuesBelowFloorForLongDomain() {
        assertEquals(listOf(0, 87), SlowDnsManager.qnameSteps("x".repeat(40), compat = false, clientIdSize = 2))
        assertEquals(listOf(0, 117), SlowDnsManager.qnameSteps("x".repeat(70), compat = false, clientIdSize = 2))
    }

    @Test
    fun stepsForTooLongDomainOnlyDefault() {
        assertEquals(listOf(0), SlowDnsManager.qnameSteps("x".repeat(210), compat = false, clientIdSize = 2))
        assertEquals(listOf(0), SlowDnsManager.qnameSteps("x".repeat(210), compat = true))
    }

    @Test
    fun stepsForCompatModeUseHigherFloor() {
        // Mode -dnstt-compat: overhead lebih besar, lantai domain + 64 = 82.
        assertEquals(listOf(0, 82), SlowDnsManager.qnameSteps("dns.nisavpn.biz.id", compat = true))
    }

    @Test
    fun nextQnameWalksDownAndStops() {
        val steps = SlowDnsManager.qnameSteps("dns.nisavpn.biz.id", compat = false, clientIdSize = 2)
        assertEquals(80, SlowDnsManager.nextQname(steps, 0, 0))
        assertEquals(70, SlowDnsManager.nextQname(steps, 80, 0))
        assertEquals(65, SlowDnsManager.nextQname(steps, 70, 0))
        assertNull(SlowDnsManager.nextQname(steps, 65, 0))
    }

    @Test
    fun nextQnameSkipsRejectedValues() {
        val steps = SlowDnsManager.qnameSteps("dns.nisavpn.biz.id", compat = false, clientIdSize = 2)
        // Klien menolak 65 (MTU di bawah minimum): dari 70 tidak ada langkah lagi.
        assertNull(SlowDnsManager.nextQname(steps, 70, 65))
    }
}
