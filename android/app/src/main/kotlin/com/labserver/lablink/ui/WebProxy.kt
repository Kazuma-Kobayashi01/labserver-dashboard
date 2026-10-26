package com.labserver.lablink.ui

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature

/**
 * Sends every WebView in this app through the tunnel's loopback proxy. There is deliberately no DIRECT fallback: when
 * the tunnel is down, pages simply fail instead of leaking onto the open internet.
 */
object WebProxy {
    val supported: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    fun apply(context: Context, port: Int) {
        if (!supported) return
        val executor = ContextCompat.getMainExecutor(context)
        if (port > 0) {
            ProxyController.getInstance().setProxyOverride(
                ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build(), executor,
            ) {}
        } else {
            ProxyController.getInstance().clearProxyOverride(executor) {}
        }
    }
}
