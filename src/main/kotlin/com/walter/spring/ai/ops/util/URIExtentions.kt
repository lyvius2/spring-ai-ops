package com.walter.spring.ai.ops.util

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

fun URI.verifyHttpConnection(connectTimeout: Int = 3000) {
    val host = this.host ?: throw RuntimeException("Cannot resolve host from URI: '${this}'")
    val port = if (this.port != -1) this.port else when (this.scheme?.lowercase()) {
        "https" -> 443
        else -> 80
    }
    try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), connectTimeout)
        }
    } catch (e: Exception) {
        throw RuntimeException("Cannot connect to '${this}': ${e.message}")
    }
}
