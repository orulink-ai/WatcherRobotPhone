package com.danielealbano.androidremotecontrolmcp.data.model

/** Cloudflare tunnel operating mode. */
enum class CloudflareTunnelMode {
    /** Quick Tunnel — no account, random *.trycloudflare.com URL per session. */
    FREE,

    /** Named tunnel run with a dashboard token — static hostname(s) from remote config. */
    TOKEN,
}
