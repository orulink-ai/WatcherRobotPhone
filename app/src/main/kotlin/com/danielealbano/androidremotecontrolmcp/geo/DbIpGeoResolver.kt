package com.danielealbano.androidremotecontrolmcp.geo

import android.content.Context
import android.net.InetAddresses
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [GeoIpResolver] backed by the bundled compact [LocationDb]. The asset is shipped gzipped
 * (`location-db.bin.gz`); aapt stores it deflate-compressed in the APK and serves it DECOMPRESSED under
 * the `.gz`-stripped name (`geo/location-db.bin`), so we copy that raw stream to [Context.getFilesDir]
 * once per app version and memory-map it — the ~100 MB table is disk-backed paged memory, not heap.
 * Private / loopback / link-local addresses are not geolocated. Any failure (missing asset, parse error)
 * degrades to null — the location is purely informational.
 */
@Singleton
class DbIpGeoResolver
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : GeoIpResolver {
        @Volatile
        private var db: LocationDb? = null
        private var loadAttempted = false
        private val lock = Any()

        /**
         * Numeric-IP parser seam. Production uses [InetAddresses.parseNumericAddress], which NEVER performs
         * DNS, so an untrusted header value cannot trigger a lookup. Overridable in tests (the Android class
         * is absent from the JVM test classpath).
         */
        internal var numericParser: (String) -> InetAddress? = { InetAddresses.parseNumericAddress(it) }

        override fun resolve(ip: String?): GeoLocation? {
            val address = parseNumeric(ip)?.takeUnless { isNonRoutable(it) } ?: return null
            return database()?.lookup(address)
        }

        private fun isNonRoutable(a: InetAddress): Boolean =
            a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress || a.isSiteLocalAddress

        private fun parseNumeric(ip: String?): InetAddress? {
            val trimmed = ip?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching { numericParser(trimmed) }.getOrNull()
        }

        private fun database(): LocationDb? {
            db?.let { return it }
            return synchronized(lock) {
                if (db == null && !loadAttempted) {
                    loadAttempted = true
                    db =
                        runCatching { load() }
                            .onFailure { Log.w(TAG, "location db unavailable: ${it.message}") }
                            .getOrNull()
                }
                db
            }
        }

        private fun load(): LocationDb {
            val target = File(context.filesDir, "geo/location-db-${BuildConfig.VERSION_CODE}.bin")
            if (!target.exists()) extractAsset(target)
            return mapReadOnly(target)
        }

        private fun extractAsset(target: File) {
            target.parentFile?.mkdirs()
            cleanStaleCopies(target)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            // aapt already decompressed the .gz source, so the asset stream is the raw LDB1 blob.
            context.assets.open(ASSET).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            check(tmp.renameTo(target)) { "could not finalize location db" }
        }

        // The mapping stays valid after the channel/file are closed (FileChannel.map contract).
        private fun mapReadOnly(file: File): LocationDb =
            RandomAccessFile(file, "r").use { raf ->
                raf.channel.use { channel ->
                    LocationDb(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()))
                }
            }

        // Best-effort removal of previous-version copies (disk hygiene only; mapping correctness is unaffected).
        private fun cleanStaleCopies(keep: File) {
            keep.parentFile?.listFiles { f -> f.name.startsWith("location-db-") && f != keep }?.forEach { it.delete() }
        }

        private companion object {
            const val TAG = "MCP:GeoIpResolver"

            // aapt strips the `.gz` and serves the decompressed bytes, so we open the `.bin` name.
            const val ASSET = "geo/location-db.bin"
        }
    }
