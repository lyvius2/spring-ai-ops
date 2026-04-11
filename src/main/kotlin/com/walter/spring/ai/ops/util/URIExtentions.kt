package com.walter.spring.ai.ops.util

import java.net.HttpURLConnection
import java.net.URI

fun URI.verifyHttpConnection(connectTimeout: Int = 3000, readTimeout: Int = 3000) {
    try {
        val connection = toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeout
        connection.readTimeout = readTimeout
        connection.requestMethod = "GET"
        connection.connect()
        connection.disconnect()
    } catch (e: Exception) {
        throw RuntimeException("Cannot connect to '${this}': ${e.message}")
    }
}

