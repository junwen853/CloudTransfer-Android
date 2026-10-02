package io.github.junwen853.cloudtransfer.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.junwen853.cloudtransfer.data.AppDatabase
import io.github.junwen853.cloudtransfer.data.SessionStore
import io.github.junwen853.cloudtransfer.data.Settings
import io.github.junwen853.cloudtransfer.icloud.AppleHttp
import io.github.junwen853.cloudtransfer.icloud.CloudKitClient
import io.github.junwen853.cloudtransfer.icloud.Cookies
import io.github.junwen853.cloudtransfer.icloud.DatabaseRequestEvidence
import io.github.junwen853.cloudtransfer.icloud.ICloudEndpoints
import io.github.junwen853.cloudtransfer.icloud.ICloudSession
import io.github.junwen853.cloudtransfer.icloud.SessionAccess
import io.github.junwen853.cloudtransfer.icloud.WebAccessDeniedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * The iCloud Photos webpage inside the app. The user signs in there (the app
 * never sees the password); once the photo grid loads, the app takes over the
 * web session: the cookies and the photo database address the page uses.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val appSettings = remember { Settings(context) }
    val scope = rememberCoroutineScope()
    var region by remember { mutableIntStateOf(appSettings.region) }
    var status by remember { mutableStateOf("请在下方登录 Apple 账户，登录后等照片网格出现，App 会自动接管。") }
    var loading by remember { mutableIntStateOf(0) }
    var verifying by remember { mutableStateOf(false) }
    val database = remember { AtomicReference<DatabaseRequestEvidence?>(null) }
    val photosUpload = remember { AtomicReference("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var databaseSeen by remember { mutableStateOf(false) }

    fun pageUrl() = if (region == 1) ICloudEndpoints.CHINA_PHOTOS_PAGE else ICloudEndpoints.GLOBAL_PHOTOS_PAGE

    fun collectCookies(evidence: DatabaseRequestEvidence): String {
        val manager = CookieManager.getInstance()
        val home = if (evidence.databaseUrl.contains(".icloud.com.cn")) ICloudEndpoints.CHINA_HOME else ICloudEndpoints.GLOBAL_HOME
        val urls = listOf(
            home, home.replace("://www.", "://"), "$home/photos/",
            evidence.requestUrl, evidence.databaseUrl,
            photosUpload.get().ifEmpty { runCatching { ICloudEndpoints.derivePhotosUploadBase(evidence.databaseUrl) }.getOrDefault("") },
        ).filter { it.isNotEmpty() }
        var header = ""
        for (url in urls) header = Cookies.merge(header, manager.getCookie(url))
        return header
    }

    fun tryCapture() {
        if (verifying) return
        val evidence = database.get() ?: return
        CookieManager.getInstance().flush()
        val cookies = collectCookies(evidence)
        if (!Cookies.hasPhotosPcs(cookies)) {
            status = "已识别你的照片库，正在等照片网格加载完成……"
            return
        }
        val session = ICloudSession(
            cookieHeader = cookies,
            databaseUrl = evidence.databaseUrl,
            dsid = evidence.dsid,
            clientId = evidence.clientId,
            clientBuildNumber = evidence.clientBuildNumber,
            clientMasteringNumber = evidence.clientMasteringNumber,
            photosUploadUrl = photosUpload.get(),
        )
        verifying = true
        status = "已拿到登录信息，正在向 iCloud 验证……"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var current = session
                    val access = object : SessionAccess {
                        override fun current() = current

                        @Synchronized
                        override fun applySetCookies(setCookieHeaders: List<String>) {
                            current = current.copy(cookieHeader = Cookies.applySetCookies(current.cookieHeader, setCookieHeaders))
                        }
                    }
                    val total = CloudKitClient(AppleHttp(access), access).count(CloudKitClient.COUNT_VISIBLE)
                    val store = SessionStore.get(context)
                    val previous = store.load()
                    if (previous != null && previous.dsid != current.dsid) AppDatabase.get(context).clearAccountState()
                    store.save(current)
                    total
                }
            }
            result.onSuccess { total ->
                status = "登录成功：iCloud 照片里有 $total 张（不含隐藏相簿）。"
                delay(800)
                onDone("已登录 iCloud，照片库共 $total 张。")
            }.onFailure { error ->
                verifying = false
                status = if (error is WebAccessDeniedException) error.message.orEmpty() else "验证没通过：${error.message}。请等照片网格加载后再试，或点“刷新”。"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }

    // The Photos cookie appears only after the photo grid has loaded.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2500)
            if (database.get() != null && !verifying) tryCapture()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("登录 iCloud", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全球 iCloud", "中国大陆 iCloud").forEachIndexed { index, label ->
                    if (region == index) {
                        Button(onClick = {}) { Text(label) }
                    } else {
                        OutlinedButton(onClick = {
                            region = index
                            appSettings.region = index
                            database.set(null)
                            photosUpload.set("")
                            databaseSeen = false
                            webView?.loadUrl(pageUrl())
                        }) { Text(label) }
                    }
                }
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (loading in 1..99) LinearProgressIndicator(progress = { loading / 100f }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { webView?.reload() }) { Text("刷新") }
                OutlinedButton(onClick = { onDone(null) }) { Text("返回") }
                Button(onClick = { tryCapture() }, enabled = databaseSeen && !verifying) { Text("用这个登录") }
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.userAgentString = ICloudEndpoints.DESKTOP_USER_AGENT
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            loading = newProgress
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val url = request?.url?.toString() ?: return null
                            ICloudEndpoints.parsePhotosUploadRequest(url)?.let { photosUpload.set(it) }
                            ICloudEndpoints.parsePhotosDatabaseRequest(url)?.let { evidence ->
                                val previous = database.get()
                                database.set(
                                    evidence.copy(
                                        clientId = evidence.clientId.ifEmpty { previous?.clientId.orEmpty() },
                                        clientBuildNumber = evidence.clientBuildNumber.ifEmpty { previous?.clientBuildNumber.orEmpty() },
                                        clientMasteringNumber = evidence.clientMasteringNumber.ifEmpty { previous?.clientMasteringNumber.orEmpty() },
                                    ),
                                )
                                if (previous == null) {
                                    view?.post {
                                        databaseSeen = true
                                        tryCapture()
                                    }
                                }
                            }
                            return null
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            loading = 1
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = 100
                            view?.postDelayed({ tryCapture() }, 3000)
                        }
                    }
                    loadUrl(pageUrl())
                    webView = this
                }
            },
        )
    }
}
