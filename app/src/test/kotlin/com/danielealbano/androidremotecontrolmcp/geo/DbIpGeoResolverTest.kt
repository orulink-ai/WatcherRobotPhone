package com.danielealbano.androidremotecontrolmcp.geo

import android.content.Context
import android.content.res.AssetManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetAddress

@DisplayName("DbIpGeoResolver")
class DbIpGeoResolverTest {
    private lateinit var assets: AssetManager
    private lateinit var context: Context
    private lateinit var resolver: DbIpGeoResolver

    @BeforeEach
    fun setUp(
        @TempDir tmp: File,
    ) {
        assets = mockk()
        every { assets.open(any()) } throws IOException("no asset") // simulate a missing/unreadable asset
        context = mockk()
        every { context.filesDir } returns tmp
        every { context.assets } returns assets

        resolver = DbIpGeoResolver(context)
        // Deterministic, DNS-free parser for the test (the Android parser is absent on the JVM classpath).
        val known =
            listOf("127.0.0.1", "10.0.0.5", "::1", "169.254.1.1", "8.8.8.8", "8.8.4.4")
                .associateWith { InetAddress.getByName(it) }
        resolver.numericParser = { known[it] }
    }

    @Test
    @DisplayName("does not geolocate private / loopback / link-local addresses (and never opens the DB)")
    fun skipsNonRoutable() {
        assertNull(resolver.resolve("127.0.0.1")) // loopback
        assertNull(resolver.resolve("10.0.0.5")) // private
        assertNull(resolver.resolve("::1")) // IPv6 loopback
        assertNull(resolver.resolve("169.254.1.1")) // link-local
        verify(exactly = 0) { assets.open(any()) }
    }

    @Test
    @DisplayName("returns null for malformed / blank / null input without throwing")
    fun rejectsMalformed() {
        assertNull(resolver.resolve("not-an-ip"))
        assertNull(resolver.resolve(""))
        assertNull(resolver.resolve("   "))
        assertNull(resolver.resolve(null))
    }

    @Test
    @DisplayName("degrades to null when the asset is unavailable and only attempts to load once")
    fun missingAssetDegradesGracefully() {
        assertNull(resolver.resolve("8.8.8.8")) // routable -> attempts load -> fails -> null
        assertNull(resolver.resolve("8.8.4.4")) // load already attempted -> no second open
        verify(exactly = 1) { assets.open(any()) }
    }
}
