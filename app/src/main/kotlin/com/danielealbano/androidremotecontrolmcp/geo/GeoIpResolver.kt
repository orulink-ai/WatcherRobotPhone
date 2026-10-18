package com.danielealbano.androidremotecontrolmcp.geo

/** Resolves a textual client IP to a [GeoLocation], or null when unknown / private / unavailable. */
fun interface GeoIpResolver {
    fun resolve(ip: String?): GeoLocation?
}
