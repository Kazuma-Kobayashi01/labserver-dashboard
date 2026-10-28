package com.labserver.lablink.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.HttpAuthHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.labserver.lablink.R

private class AuthRequest(val handler: HttpAuthHandler, val host: String, val realm: String?)

/**
 * A full-screen panel. The WebView can only navigate to the home domain, and all of its traffic goes through the in-app
 * tunnel (see [WebProxy]); it never reaches the open internet and nothing here can be opened in another browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PanelWebView(url: String, domain: String, tunnelHost: String, title: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var auth by remember { mutableStateOf<AuthRequest?>(null) }
    val blockedMsg = stringResource(R.string.web_blocked)
    val errorMsg = stringResource(R.string.web_error)

    BackHandler { if (canGoBack) web?.goBack() else onClose() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent, scrolledContainerColor = glassFill(strong = true)),
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.web_close)) }
            },
            actions = {
                IconButton(onClick = { web?.loadUrl(url) }) { Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.web_reload)) }
            },
            windowInsets = WindowInsets.statusBars,
        )
        if (!WebProxy.supported) {
            Text(stringResource(R.string.web_unsupported), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        WebView(c).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            webViewClient = object : WebViewClient() {
                                private fun allowed(host: String?) =
                                    host != null && (host == tunnelHost || host == domain || host.endsWith(".$domain"))

                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val ok = allowed(request.url.host)
                                    if (!ok) Toast.makeText(ctx, blockedMsg, Toast.LENGTH_SHORT).show()
                                    return !ok // true = do not navigate
                                }

                                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                    canGoBack = view.canGoBack()
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    canGoBack = view.canGoBack()
                                }

                                override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String?) {
                                    auth = AuthRequest(handler, host, realm)
                                }

                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) {
                                        view.loadDataWithBaseURL(null, "<meta name=viewport content='width=device-width'><body style='font-family:sans-serif;padding:32px;color:#888'><h3>$errorMsg</h3><p>${error.description}</p>", "text/html", "utf-8", null)
                                    }
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress
                                }
                            }
                            loadUrl(url)
                            web = this
                        }
                    },
                    onRelease = { it.destroy() },
                )
                if (progress in 1..99) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    auth?.let { req ->
        var user by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { req.handler.cancel(); auth = null },
            title = { Text(stringResource(R.string.web_login)) },
            text = {
                Column {
                    Text(req.host + (req.realm?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.web_user)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    OutlinedTextField(
                        pass, { pass = it }, label = { Text(stringResource(R.string.web_password)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { req.handler.proceed(user, pass); auth = null }) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { TextButton(onClick = { req.handler.cancel(); auth = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
