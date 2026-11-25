package com.v2ray.ang.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SlowDnsQnameStepsTest {

    @Test
    fun stepsForShortDomainEndAtFloor() {
        // "dns.nisavpn.biz.id" = 18 karakter, jadi batas terkecil 18 + 64 = 82.
        assertEquals(listOf(0, 130, 100, 82), SlowDnsManager.qnameSteps("dns.nisavpn.biz.id"))
    }

    @Test
    fun stepsDropValuesBelowFloorForLongDomain() {
        assertEquals(listOf(0, 130, 104), SlowDnsManager.qnameSteps("x".repeat(40)))
        assertEquals(listOf(0, 134), SlowDnsManager.qnameSteps("x".repeat(70)))
    }

    @Test
    fun stepsForTooLongDomainOnlyDefault() {
        assertEquals(listOf(0), SlowDnsManager.qnameSteps("x".repeat(200)))
    }

    @Test
    fun nextQnameWalksDownAndStops() {
        val steps = SlowDnsManager.qnameSteps("dns.nisavpn.biz.id")
        assertEquals(130, SlowDnsManager.nextQname(steps, 0, 0))
        assertEquals(100, SlowDnsManager.nextQname(steps, 130, 0))
        assertEquals(82, SlowDnsManager.nextQname(steps, 100, 0))
        assertNull(SlowDnsManager.nextQname(steps, 82, 0))
    }

    @Test
    fun nextQnameSkipsRejectedValues() {
        val steps = SlowDnsManager.qnameSteps("dns.nisavpn.biz.id")
        // Klien menolak 82 (MTU di bawah minimum): dari 100 tidak ada langkah lagi.
        assertNull(SlowDnsManager.nextQname(steps, 100, 82))
    }
}
