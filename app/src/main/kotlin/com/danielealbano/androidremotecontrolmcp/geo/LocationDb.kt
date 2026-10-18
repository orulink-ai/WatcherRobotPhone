package com.danielealbano.androidremotecontrolmcp.geo

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Resolved geolocation: a 2-letter ISO country code and/or a city name (either may be null/unknown). */
data class GeoLocation(
    val countryCode: String?,
    val city: String?,
)

/**
 * Reader for the compact `LDB1` location database produced by `scripts/location-db`. It operates over a
 * [ByteBuffer] (in production a read-only `MappedByteBuffer`, so the 100+ MB table is disk-backed paged
 * memory, never the Java heap) and resolves an [InetAddress] to a [GeoLocation] via a binary search of the
 * sorted, contiguous per-family range table. See the Python `location_db.py` docstring for the byte layout.
 */
class LocationDb(
    private val buf: ByteBuffer,
) {
    private val countryBase: Int
    private val cityCount: Int
    private val cityOffsetsBase: Int
    private val cityBlobBase: Int
    private val locBase: Int
    private val v4Count: Int
    private val v4StartsBase: Int
    private val v4LocIdsBase: Int
    private val v6Count: Int
    private val v6StartsBase: Int
    private val v6LocIdsBase: Int

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        require(buf.limit() >= HEADER_SIZE) { "truncated location db" }
        for (i in MAGIC.indices) require(buf.get(i) == MAGIC[i]) { "not an LDB1 database" }
        require(buf.get(VERSION_OFFSET).toInt() == VERSION) { "unsupported location db version" }
        val flags = buf.get(FLAGS_OFFSET).toInt()

        var pos = HEADER_SIZE
        val countryCount = u16(pos)
        pos += U16_SIZE
        countryBase = pos
        pos += countryCount * U16_SIZE

        cityCount = u32(pos)
        pos += U32_SIZE
        cityOffsetsBase = pos
        pos += (cityCount + 1) * U32_SIZE
        cityBlobBase = pos
        pos += u32(cityOffsetsBase + cityCount * U32_SIZE) // blob length = last offset

        val locCount = u32(pos)
        pos += U32_SIZE
        locBase = pos
        pos += locCount * LOC_ENTRY_SIZE

        v4Count = u32(pos)
        pos += U32_SIZE
        v4StartsBase = pos
        pos += v4Count * U32_SIZE
        v4LocIdsBase = pos
        pos += v4Count * U24_SIZE

        if (flags and FLAG_HAS_IPV6 != 0) {
            v6Count = u32(pos)
            pos += U32_SIZE
            v6StartsBase = pos
            pos += v6Count * IPV6_SIZE
            v6LocIdsBase = pos
        } else {
            v6Count = 0
            v6StartsBase = 0
            v6LocIdsBase = 0
        }
    }

    /** Resolves [address] to a [GeoLocation], or null when the address is unknown / not in the DB. */
    fun lookup(address: InetAddress): GeoLocation? =
        when (address) {
            is Inet4Address -> {
                var ip = 0L
                for (b in address.address) ip = (ip shl BYTE_BITS) or (b.toLong() and BYTE_MASK_L)
                resolve(locIdAt(v4LocIdsBase, ipv4Index(ip)))
            }

            is Inet6Address -> {
                resolve(locIdAt(v6LocIdsBase, ipv6Index(address.address)))
            }

            else -> {
                null
            }
        }

    // -- range search (bisect_right(starts, ip) - 1) --

    private fun ipv4Index(ip: Long): Int {
        var lo = 0
        var hi = v4Count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val start = buf.getInt(v4StartsBase + mid * U32_SIZE).toLong() and U32_MASK_L // unsigned
            if (start <= ip) lo = mid + 1 else hi = mid
        }
        return lo - 1
    }

    /** 16-byte big-endian starts: a byte-wise unsigned compare equals the numeric one. */
    private fun ipv6Index(ip: ByteArray): Int {
        var lo = 0
        var hi = v6Count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareUnsigned16(v6StartsBase + mid * IPV6_SIZE, ip) <= 0) lo = mid + 1 else hi = mid
        }
        return lo - 1
    }

    private fun locIdAt(
        base: Int,
        index: Int,
    ): Int = if (index < 0) UNKNOWN_LOC else u24(base + index * U24_SIZE)

    private fun resolve(locId: Int): GeoLocation? {
        if (locId == UNKNOWN_LOC) return null
        val entry = locBase + locId * LOC_ENTRY_SIZE
        val codePos = countryBase + u16(entry) * U16_SIZE
        val a = buf.get(codePos)
        val code = if (a.toInt() == 0) null else String(byteArrayOf(a, buf.get(codePos + 1)), Charsets.US_ASCII)
        val city = cityName(u32(entry + U16_SIZE))
        return if (code == null && city == null) null else GeoLocation(code, city)
    }

    private fun cityName(idx: Int): String? {
        val start = u32(cityOffsetsBase + idx * U32_SIZE)
        val end = u32(cityOffsetsBase + (idx + 1) * U32_SIZE)
        if (end <= start) return null
        val bytes = ByteArray(end - start)
        for (i in bytes.indices) bytes[i] = buf.get(cityBlobBase + start + i)
        return String(bytes, Charsets.UTF_8)
    }

    // -- primitive reads (counts/offsets fit in a positive Int for this DB) --

    private fun u16(pos: Int): Int = buf.getShort(pos).toInt() and U16_MASK

    private fun u32(pos: Int): Int = buf.getInt(pos)

    private fun u24(pos: Int): Int {
        val b0 = buf.get(pos).toInt() and BYTE_MASK
        val b1 = buf.get(pos + 1).toInt() and BYTE_MASK
        val b2 = buf.get(pos + 2).toInt() and BYTE_MASK
        return b0 or (b1 shl BYTE_BITS) or (b2 shl SHORT_BITS)
    }

    private fun compareUnsigned16(
        bufPos: Int,
        other: ByteArray,
    ): Int {
        for (i in 0 until IPV6_SIZE) {
            val a = buf.get(bufPos + i).toInt() and BYTE_MASK
            val b = other[i].toInt() and BYTE_MASK
            if (a != b) return a - b
        }
        return 0
    }

    private companion object {
        val MAGIC = "LDB1".toByteArray(Charsets.US_ASCII)
        const val HEADER_SIZE = 8
        const val VERSION_OFFSET = 4
        const val FLAGS_OFFSET = 5
        const val VERSION = 1
        const val FLAG_HAS_IPV6 = 0x01
        const val UNKNOWN_LOC = 0
        const val LOC_ENTRY_SIZE = 6 // country_idx u16 + city_idx u32
        const val U16_SIZE = 2
        const val U24_SIZE = 3
        const val U32_SIZE = 4
        const val IPV6_SIZE = 16
        const val BYTE_BITS = 8
        const val SHORT_BITS = 16
        const val BYTE_MASK = 0xFF
        const val BYTE_MASK_L = 0xFFL
        const val U16_MASK = 0xFFFF
        const val U32_MASK_L = 0xFFFFFFFFL
    }
}
