package com.magpie.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DnsResolverTest {
    @Test fun emptyDnsListProducesEmptyFile() {
        assertEquals("", DnsResolver.toResolvConf(emptyList()))
    }

    @Test fun includesIpv4AndIpv6InOrderWithFinalNewline() {
        assertEquals("nameserver 192.0.2.53\nnameserver 2001:db8::53\n", DnsResolver.toResolvConf(listOf("192.0.2.53", "2001:db8::53")))
    }

    @Test fun limitsOutputToFirstThreeServers() {
        assertEquals("nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 9.9.9.9\n", DnsResolver.toResolvConf(listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "4.4.4.4")))
    }
}
