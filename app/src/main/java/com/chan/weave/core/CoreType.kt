package com.chan.weave.core

/**
 * Proxy core engine. Weave is Mist-only since 1.0.30:
 * Xray was fully removed (use SwiftNG for Xray).
 * This enum is kept because [com.chan.weave.data.Node] stores it;
 * it has a single value now.
 */
enum class CoreType(val displayName: String) {
    MIST("Mist");

    companion object {
        fun fromName(name: String): CoreType = MIST
    }
}
