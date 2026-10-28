package com.labserver.lablink.ui

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.labserver.lablink.R
import com.labserver.lablink.labApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

private class AuthRequest(val handler: HttpAuthHandler, val host: String, val realm: String?)
private class SslRequest(val handler: SslErrorHandler, val host: String)

/**
 * A full-screen home panel in the phone's own Chromium (Android System WebView, kept current by the Play Store). It is set
 * up as a desktop-class browser so the home lab's admin pages lay out as they should: wide viewport, page text at 100 %
 * whatever the system font size, cookies across the home subdomains, file uploads, downloads, full-screen video and
 * self-signed home certificates (after asking). It only navigates to the home domain, and all of its traffic goes through
 * the in-app tunnel (see [WebProxy]); nothing here reaches the open internet.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PanelWebView(url: String, domain: String, tunnelHost: String, title: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.labApp
    val scope = rememberCoroutineScope()
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf(title) }
    var host by remember { mutableStateOf(Uri.parse(url).host.orEmpty()) }
    var secure by remember { mutableStateOf(url.startsWith("https")) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var auth by remember { mutableStateOf<AuthRequest?>(null) }
    var ssl by remember { mutableStateOf<SslRequest?>(null) }
    var fullscreen by remember { mutableStateOf<Pair<View, WebChromeClient.CustomViewCallback>?>(null) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val blockedMsg = stringResource(R.string.web_blocked)
    val errorMsg = stringResource(R.string.web_error)
    val errorHint = stringResource(R.string.web_error_hint)
    val dark = isDark()

    // a page asks for files (an upload form): the system picker answers it
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        fileCallback?.onReceiveValue(uris.toTypedArray())
        fileCallback = null
    }

    BackHandler {
        when {
            fullscreen != null -> fullscreen?.second?.onCustomViewHidden()
            canGoBack -> web?.goBack()
            else -> onClose()
        }
    }

    fun allowed(h: String?) = h != null && (h == tunnelHost || h == domain || h.endsWith(".$domain"))

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize()) {
            // the bar: close, the page in a glass capsule (lock, title, host), back / forward / reload
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(Icons.Rounded.Close, stringResource(R.string.web_close), onClose)
                Row(
                    Modifier.weight(1f).padding(horizontal = 8.dp).glass(RoundedCornerShape(50), elevation = 4.dp, strong = true)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (secure) Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.padding(end = 6.dp).width(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(pageTitle.ifBlank { title }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(host, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (canGoBack) GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.web_back), { web?.goBack() }, size = 40.dp)
                if (canGoForward) {
                    Spacer(Modifier.width(6.dp))
                    GlassIconButton(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.web_forward), { web?.goForward() }, size = 40.dp)
                }
                Spacer(Modifier.width(6.dp))
                GlassIconButton(Icons.Rounded.Refresh, stringResource(R.string.web_reload), { web?.reload() }, size = 40.dp)
            }
            // loading: a thin line in the brand's colours, fading out when done
            val shown by animateFloatAsState(progress / 100f, label = "progress")
            Box(Modifier.fillMaxWidth().height(3.dp)) {
                androidx.compose.animation.AnimatedVisibility(progress in 1..99, exit = fadeOut()) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(shown).clip(RoundedCornerShape(50))
                            .background(Brush.horizontalGradient(listOf(Color(0xFF5546E0), Color(0xFFC77DFF)))),
                    )
                }
            }
            if (!WebProxy.supported) {
                Text(stringResource(R.string.web_unsupported), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
            } else {
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { c ->
                        WebView(c).apply {
                            setBackgroundColor(if (dark) 0xFF131318.toInt() else 0xFFFFFFFF.toInt())
                            with(settings) {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                // desktop-class pages: honour their viewport, start zoomed to fit, and allow pinch zoom
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                // the system font size would otherwise scale page text and break their layouts
                                textZoom = 100
                                mediaPlaybackRequiresUserGesture = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                allowFileAccess = false
                                allowContentAccess = false
                                javaScriptCanOpenWindowsAutomatically = false
                                setSupportMultipleWindows(false)
                            }
                            // logins that hop between home subdomains (single sign-on) need their cookies
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val ok = allowed(request.url.host)
                                    if (!ok) Toast.makeText(ctx, blockedMsg, Toast.LENGTH_SHORT).show()
                                    return !ok // true = do not navigate
                                }

                                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                    canGoBack = view.canGoBack(); canGoForward = view.canGoForward()
                                    url?.let { host = Uri.parse(it).host.orEmpty(); secure = it.startsWith("https") }
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    canGoBack = view.canGoBack(); canGoForward = view.canGoForward()
                                    view.title?.takeIf { it.isNotBlank() && !it.startsWith("http") }?.let { pageTitle = it }
                                }

                                override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String?) {
                                    auth = AuthRequest(handler, host, realm)
                                }

                                // a home site with a self-signed certificate: ask once, never for anything outside home
                                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                    val h = Uri.parse(error.url).host
                                    if (allowed(h)) ssl = SslRequest(handler, h.orEmpty()) else handler.cancel()
                                }

                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) view.loadDataWithBaseURL(null, errorPage(errorMsg, errorHint, error.description.toString(), dark), "text/html", "utf-8", null)
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                                override fun onReceivedTitle(view: WebView, t: String?) { if (!t.isNullOrBlank() && !t.startsWith("http")) pageTitle = t }

                                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                                    fileCallback?.onReceiveValue(null)
                                    fileCallback = callback
                                    val type = params.acceptTypes.firstOrNull { it.isNotBlank() } ?: "*/*"
                                    return try { pickFiles.launch(type); true } catch (_: Exception) { fileCallback = null; false }
                                }

                                // full-screen video and other full-screen content
                                override fun onShowCustomView(view: View, callback: CustomViewCallback) { fullscreen = view to callback }
                                override fun onHideCustomView() { fullscreen = null }
                            }
                            // downloads go through the tunnel too (the system download manager could not reach home)
                            setDownloadListener { dlUrl, userAgent, disposition, mime, _ ->
                                if (!allowed(Uri.parse(dlUrl).host)) return@setDownloadListener
                                val name = URLUtil.guessFileName(dlUrl, disposition, mime)
                                Toast.makeText(ctx, ctx.getString(R.string.web_downloading, name), Toast.LENGTH_SHORT).show()
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        download(dlUrl, userAgent, CookieManager.getInstance().getCookie(dlUrl), app.tunnel.proxyPort.value, name, mime, ctx)
                                    }
                                    Toast.makeText(ctx, ctx.getString(if (ok) R.string.web_downloaded else R.string.web_download_failed, name), Toast.LENGTH_SHORT).show()
                                }
                            }
                            loadUrl(url)
                            web = this
                        }
                    },
                    onRelease = { it.stopLoading(); it.destroy() },
                )
            }
        }

        // full-screen content covers everything
        fullscreen?.let { (view, _) ->
            AndroidView(
                factory = { (view.parent as? ViewGroup)?.removeView(view); view },
                modifier = Modifier.fillMaxSize().background(Color.Black),
            )
        }
    }

    auth?.let { req ->
        var user by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        LabAlertDialog(
            onDismissRequest = { req.handler.cancel(); auth = null },
            title = { Text(stringResource(R.string.web_login)) },
            text = {
                Column {
                    Text(req.host + (req.realm?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    LabTextField(user, { user = it }, label = { Text(stringResource(R.string.web_user)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    LabTextField(
                        pass, { pass = it }, label = { Text(stringResource(R.string.web_password)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { PillButton(onClick = { req.handler.proceed(user, pass); auth = null }) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { PillButton(onClick = { req.handler.cancel(); auth = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    ssl?.let { req ->
        LabAlertDialog(
            onDismissRequest = { req.handler.cancel(); ssl = null },
            title = { Text(stringResource(R.string.web_ssl_title)) },
            text = { Text(stringResource(R.string.web_ssl_text, req.host)) },
            confirmButton = { PillButton(onClick = { req.handler.proceed(); ssl = null }) { Text(stringResource(R.string.web_ssl_go)) } },
            dismissButton = { PillButton(onClick = { req.handler.cancel(); ssl = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** The page shown when a home page cannot be loaded: calm, in the app's colours, light or dark. */
private fun errorPage(title: String, hint: String, detail: String, dark: Boolean): String {
    val bg = if (dark) "#131318" else "#FCFAFF"
    val fg = if (dark) "#E5E1EC" else "#1B1B21"
    val muted = if (dark) "#9A97A8" else "#6E6B7D"
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
    return """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<body style="margin:0;background:$bg;color:$fg;font-family:system-ui,sans-serif;display:flex;align-items:center;justify-content:center;min-height:90vh;text-align:center">
<div style="padding:32px"><div style="width:72px;height:72px;margin:0 auto 18px;border-radius:50%;background:linear-gradient(135deg,#5546E0,#C77DFF);opacity:.85"></div>
<h2 style="margin:0 0 8px">${esc(title)}</h2><p style="margin:0 0 6px;color:$muted">${esc(hint)}</p><p style="margin:0;color:$muted;font-size:12px">${esc(detail)}</p></div>"""
}

/** Fetches [url] through the tunnel with the page's cookies and saves it to Downloads; false when it could not. */
private fun download(url: String, userAgent: String?, cookies: String?, port: Int, name: String, mime: String?, ctx: android.content.Context): Boolean = try {
    if (Build.VERSION.SDK_INT < 29 || port == 0) false else {
        val client = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
        val req = Request.Builder().url(url).apply {
            if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent)
            if (!cookies.isNullOrBlank()) header("Cookie", cookies)
        }.build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime ?: resp.header("Content-Type") ?: "application/octet-stream")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val r = ctx.contentResolver
            val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            r.openOutputStream(uri)!!.use { out -> resp.body.byteStream().use { it.copyTo(out) } }
            r.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            true
        }
    }
} catch (e: Exception) {
    android.util.Log.w("LabLink", "download failed", e)
    false
}
