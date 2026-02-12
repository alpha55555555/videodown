package com.example.douyindownloader

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import java.io.File
import java.io.FileOutputStream
import java.util.regex.Pattern
import java.util.concurrent.TimeUnit

fun Modifier.verticalScrollbar(state: ScrollState): Modifier = drawWithContent {
    drawContent()
    val visibleHeight = size.height
    val contentHeight = state.maxValue.toFloat() + visibleHeight
    
    if (state.maxValue > 0) {
        val viewableRatio = visibleHeight / contentHeight
        // Cap the scrollbar height at 90% of visible height so it always looks like a scrollbar
        val scrollbarHeight = (visibleHeight * viewableRatio).coerceIn(20.dp.toPx(), visibleHeight * 0.9f)
        
        val scrollProgress = state.value.toFloat() / state.maxValue.toFloat()
        val offset = scrollProgress * (visibleHeight - scrollbarHeight)
        
        drawRect(
            color = Color.Gray.copy(alpha = 0.5f),
            topLeft = Offset(size.width - 4.dp.toPx(), offset),
            size = Size(4.dp.toPx(), scrollbarHeight)
        )
    }
}

fun Modifier.lazyVerticalScrollbar(state: LazyListState): Modifier = drawWithContent {
    drawContent()
    val totalItems = state.layoutInfo.totalItemsCount
    if (totalItems > 0) {
        val visibleItems = state.layoutInfo.visibleItemsInfo.size
        val length = size.height * (visibleItems.toFloat() / totalItems.toFloat()).coerceIn(0.1f, 1f)
        val firstVisibleIndex = state.firstVisibleItemIndex
        val offset = (firstVisibleIndex.toFloat() / totalItems.toFloat()) * size.height
        drawRect(color = Color.Gray.copy(alpha = 0.5f), topLeft = Offset(size.width - 4.dp.toPx(), offset), size = Size(4.dp.toPx(), length))
    }
}

// Global OkHttpClient to share connection pool and reduce resource usage
val globalOkHttpClient = OkHttpClient.Builder()
    .followRedirects(true)
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

// 数据类：严格定义参数顺序
data class DownloadHistory(
    val name: String,
    val path: String,
    val time: Long,
    val status: Int, // 0: Downloading, 1: Success, 2: Failed, 3: Unplayable
    val url: String,
    val origin: String = "", // Original Share Link
    val progress: Float = 0f
)

fun isValidMediaFile(path: String): Boolean {
    val file = File(path)
    if (!file.exists() || file.length() < 1024) return false // Too small to be media
    
    val ext = path.substringAfterLast(".", "").lowercase()
    if (ext == "jpg" || ext == "jpeg" || ext == "webp" || ext == "gif" || ext == "png") {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, options)
        return options.outWidth > 0 && options.outHeight > 0
    }
    
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
        val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
        hasVideo != null || hasAudio != null
    } catch (e: Exception) {
        false
    } finally {
        try {
            retriever.release()
        } catch (e: Exception) {}
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent { videodownApp() }
    }
}

@Composable
fun videodownApp() {
    val context = LocalContext.current
    var currentTab by remember { mutableIntStateOf(0) }
    val history = remember { mutableStateListOf<DownloadHistory>() }
    var saveDir by remember { mutableStateOf("videodownData") }
    var selectedSource by remember { mutableStateOf("Bilibili") } // "Bilibili", "Douyin" or "Twitter"

    // Hoisted State for Input
    var downloadInput by remember { mutableStateOf("") }
    var autoTriggerDownload by remember { mutableStateOf(false) }
    var processingOrigin by remember { mutableStateOf("") } // Stores the full input text (title + url)

    // Hoisted State for Debugger
    var debugUrlInput by remember { mutableStateOf("") }
    var debugSniffingUrl by remember { mutableStateOf("") }
    var debugStatusMessage by remember { mutableStateOf("等待输入") }
    val debugCapturedUrls = remember { mutableStateListOf<String>() }

    // Hoisted State for Background Execution
    var status by remember { mutableStateOf("解析器已就绪") }
    var isRunning by remember { mutableStateOf(false) }
    var snifferUrl by remember { mutableStateOf<String?>(null) }
    var keepWatermark by remember { mutableStateOf(true) }
    var pendingBaseName by remember { mutableStateOf("Media") }
    val processedUrls = remember { mutableStateListOf<String>() }
    var lastActivityTime by remember { mutableLongStateOf(0L) }
    
    val scope = rememberCoroutineScope()
    val targetUA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val saved = loadHistory(context)
            withContext(Dispatchers.Main) {
                history.addAll(saved)
            }
        }
    }

    // Helper Function to handle download and history
    fun startDownloadTask(url: String, baseName: String, originUrl: String = "") {
        status = "任务已添加"

        scope.launch {
            val tempName = "${baseName}...".replace("Media...", "Media...")
            val newItem = DownloadHistory(tempName, "", System.currentTimeMillis(), 0, url, originUrl, 0f)
            history.add(0, newItem)
            
            try {
                val tempFilePrefix = "${baseName}_${System.currentTimeMillis() % 100000}"
                val res = performDownload(context, url, tempFilePrefix, saveDir, targetUA) { progress ->
                    scope.launch {
                        val idx = history.indexOfFirst { it.time == newItem.time }
                        if (idx != -1) {
                            history[idx] = history[idx].copy(progress = progress)
                        }
                    }
                }
                
                val index = history.indexOfFirst { it.time == newItem.time }
                if (index != -1) {
                    if (res != null) {
                        val savedName = File(res).name
                        val isPlayable = isValidMediaFile(res)
                        val finalStatus = if (isPlayable) 1 else 3
                        
                        history[index] = newItem.copy(name = savedName, path = res, status = finalStatus, progress = 1f)
                        status = if (isPlayable) "✅ 下载成功: $savedName" else "⚠️ 捕获到无效资源 (已自动归类)"
                        
                        withContext(Dispatchers.IO) {
                            saveHistory(context, history)
                        }
                    } else {
                        history[index] = newItem.copy(status = 2, progress = 0f)
                        status = "❌ 下载失败 (资源无效或网络错误)"
                        withContext(Dispatchers.IO) {
                            saveHistory(context, history)
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e("videodown", "Download Error", e)
                val index = history.indexOfFirst { it.time == newItem.time }
                if (index != -1) {
                    history[index] = newItem.copy(status = 2, progress = 0f)
                    withContext(Dispatchers.IO) {
                        saveHistory(context, history)
                    }
                }
            }
        }
    }
    
    // Auto-Stop Monitor with Timeout Safety
    LaunchedEffect(isRunning) {
        if (isRunning) {
            lastActivityTime = System.currentTimeMillis()
            val startTime = System.currentTimeMillis()
            while (isActive && isRunning) {
                delay(1000)
                val now = System.currentTimeMillis()
                // 1. If we found something, and it's been quiet for 8s, we're done.
                if (processedUrls.isNotEmpty() && (now - lastActivityTime > 8000)) {
                    isRunning = false
                    snifferUrl = null
                    status = "✅ 自动解析完成"
                    break
                }
                // 2. Total duration safety (45s for slow sites like Twitter)
                if (now - startTime > 45000) {
                    val hadResults = processedUrls.isNotEmpty()
                    isRunning = false
                    snifferUrl = null
                    status = if (hadResults) "✅ 已完成 (部分资源可能加载较慢)" else "⚠️ 解析超时，请尝试在“调试”页查看详情"
                    break
                }
            }
        }
    }

    // Hidden Sniffer WebView
    if (snifferUrl != null) {
        val currentContext = LocalContext.current
        val captureTitle = pendingBaseName
        val captureOrigin = processingOrigin
        
        val webView = remember(snifferUrl) {
            WebView(currentContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString = targetUA
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun processJson(json: String) {
                        if (!isRunning) return
                        scope.launch(Dispatchers.Main) {
                            try {
                                val urlPattern = Pattern.compile("https?://video\\.twimg\\.com/[^\"\\s]+\\.mp4[^\"\\s]*")
                                val unescapedJson = json.replace("\\/", "/")
                                val urlMatcher = urlPattern.matcher(unescapedJson)
                                var found = false
                                while (urlMatcher.find()) {
                                    val url = urlMatcher.group()
                                    var bitrate = 0
                                    val context = unescapedJson.substring(maxOf(0, urlMatcher.start() - 200), minOf(unescapedJson.length, urlMatcher.end() + 200))
                                    val bitrateMatcher = Pattern.compile("\"bitrate\"\\s*:\\s*(\\d+)").matcher(context)
                                    if (bitrateMatcher.find()) bitrate = bitrateMatcher.group(1).toInt()
                                    
                                    if (!processedUrls.contains(url)) {
                                        processedUrls.add(url)
                                        lastActivityTime = System.currentTimeMillis()
                                        startDownloadTask(url, captureTitle, captureOrigin)
                                        found = true
                                    }
                                }
                                if (found) status = "已通过 API 捕获视频"
                            } catch (e: Exception) {
                                Log.e("videodown", "Twitter JSON Parse Error", e)
                            }
                        }
                    }

                    @JavascriptInterface
                    fun processVideo(url: String) {
                        if (isRunning && url.isNotEmpty() && !url.startsWith("blob:")) {
                            scope.launch(Dispatchers.Main) {
                                if (snifferUrl != null) {
                                    var finalMediaUrl = url
                                    
                                    // Bilibili specific: Strip image processing suffix
                                    if (finalMediaUrl.contains("hdslb.com") && finalMediaUrl.contains("@")) {
                                        finalMediaUrl = finalMediaUrl.substringBefore("@")
                                    }

                                    if (finalMediaUrl.contains("douyin.com") && !keepWatermark) {
                                        finalMediaUrl = finalMediaUrl.replace("playwm", "play")
                                    }

                                    // Twitter specific: Try to get original size for images
                                    if (finalMediaUrl.contains("pbs.twimg.com/media/")) {
                                        if (finalMediaUrl.contains("?format=")) {
                                            if (!finalMediaUrl.contains("&name=orig")) {
                                                finalMediaUrl = finalMediaUrl.substringBefore("&name=") + "&name=orig"
                                            }
                                        } else if (!finalMediaUrl.contains(":orig") && !finalMediaUrl.contains("?")) {
                                             finalMediaUrl = "$finalMediaUrl:orig"
                                        }
                                    }
                                    
                                    if (processedUrls.contains(finalMediaUrl)) return@launch
                                    processedUrls.add(finalMediaUrl)
                                    lastActivityTime = System.currentTimeMillis()

                                    status = "已抓取: ...${finalMediaUrl.takeLast(15)}"
                                    
                                    startDownloadTask(finalMediaUrl, captureTitle, captureOrigin)
                                }
                            }
                        }
                    }
                }, "SnifferBridge")

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        status = "正在加载页面..."
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true) {
                            status = "加载失败: ${error?.description}"
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return false
                        return !url.startsWith("http")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        status = "页面加载完成，正在嗅探媒体资源..."
                        val js = """
                            (function() {
                                var oldXHR = window.XMLHttpRequest;
                                function newXHR() {
                                    var realXHR = new oldXHR();
                                    realXHR.addEventListener('readystatechange', function() {
                                        if(realXHR.readyState == 4 && realXHR.status == 200) {
                                            var responseUrl = realXHR.responseURL || '';
                                            if (responseUrl.includes('TweetDetail') || responseUrl.includes('TweetResultByRestId')) {
                                                SnifferBridge.processJson(realXHR.responseText);
                                            }
                                        }
                                    }, false);
                                    return realXHR;
                                }
                                window.XMLHttpRequest = newXHR;

                                var oldFetch = window.fetch;
                                window.fetch = function() {
                                    return oldFetch.apply(this, arguments).then(function(response) {
                                        var responseUrl = response.url || '';
                                        if (responseUrl.includes('TweetDetail') || responseUrl.includes('TweetResultByRestId')) {
                                            var clonedResponse = response.clone();
                                            clonedResponse.text().then(function(text) {
                                                SnifferBridge.processJson(text);
                                            });
                                        }
                                        return response;
                                    });
                                };

                                setInterval(function() {
                                    var videos = document.getElementsByTagName('video');
                                    for (var i = 0; i < videos.length; i++) {
                                        var src = videos[i].currentSrc || videos[i].src;
                                        if (src) SnifferBridge.processVideo(src);
                                    }
                                    var sources = document.getElementsByTagName('source');
                                    for (var i = 0; i < sources.length; i++) {
                                        if (sources[i].src) SnifferBridge.processVideo(sources[i].src);
                                    }
                                    var images = document.getElementsByTagName('img');
                                    for(var i=0; i<images.length; i++) {
                                        var src = images[i].src;
                                        var width = images[i].naturalWidth || images[i].width;
                                        if (src && width > 300) {
                                            if (!src.includes('avatar') && !src.includes('emoji') && 
                                                !src.includes('icon') && !src.includes('logo') && 
                                                !src.includes('face') && !src.includes('brand') &&
                                                !src.includes('profile_images')) {
                                                SnifferBridge.processVideo(src);
                                            }
                                        }
                                    }
                                }, 2000);
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(js, null)
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val u = request?.url?.toString() ?: ""
                        // Enhanced Twitter video detection (including m3u8 and various twimg patterns)
                        val isVideo = u.contains("aweme/v1/play/") || u.contains("video_id") || u.contains(".mp4") || 
                                      u.contains("bilivideo.com") || u.contains("video.twimg.com") || u.contains(".m3u8") ||
                                      u.contains("ext_tw_video") || u.contains("amplify_video") || u.contains("/amplify-video/")
                        
                        // Enhanced Twitter/Large image detection
                        val isLargeImage = (u.contains("douyinpic.com") || u.contains("hdslb.com") || u.contains("pbs.twimg.com/media/") || u.matches(Regex(".*\\.(jpg|jpeg|webp|gif).*"))) && 
                                           !u.contains(".png") && !u.contains("avatar") && !u.contains("emoji") && 
                                           !u.contains("icon") && !u.contains("logo") && !u.contains("face") && 
                                           !u.contains("banner") && !u.contains(".js") && !u.contains(".css") &&
                                           !u.contains("profile_images") && !u.contains("profile_banners") && !u.contains("sticky_images") &&
                                           !u.contains("hdslb.com/bfs/archive/") && !u.contains("hdslb.com/bfs/cover/") &&
                                           !u.contains("hdslb.com/bfs/active/") && !u.contains("hdslb.com/bfs/article/")
                        
                        val isNoise = u.contains("log-upload") || u.contains("analytics") || u.contains("ads") || u.contains("doubleclick") || u.contains("api/1.1/jot") || u.contains("card_fetch")

                        if ((isVideo || isLargeImage) && !isNoise) {
                            view?.post {
                                if (isRunning) {
                                    var finalMediaUrl = if (u.contains("hdslb.com") && u.contains("@")) u.substringBefore("@") else u
                                    
                                    if (finalMediaUrl.contains("douyin.com") && !keepWatermark) {
                                        finalMediaUrl = finalMediaUrl.replace("playwm", "play")
                                    }

                                    // Twitter specific: Try to get original size for images
                                    if (finalMediaUrl.contains("pbs.twimg.com/media/")) {
                                        if (finalMediaUrl.contains("?format=")) {
                                            if (!finalMediaUrl.contains("&name=orig")) {
                                                finalMediaUrl = finalMediaUrl.substringBefore("&name=") + "&name=orig"
                                            }
                                        } else if (!finalMediaUrl.contains(":orig") && !finalMediaUrl.contains("?")) {
                                             finalMediaUrl = "$finalMediaUrl:orig"
                                        }
                                    }
                                    
                                    if (!processedUrls.contains(finalMediaUrl)) {
                                        processedUrls.add(finalMediaUrl)
                                        lastActivityTime = System.currentTimeMillis()
                                        startDownloadTask(finalMediaUrl, captureTitle, captureOrigin)
                                    }
                                }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                loadUrl(snifferUrl!!)
            }
        }

        DisposableEffect(webView) {
            onDispose {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        }

        Box(modifier = Modifier.size(0.dp)) { AndroidView(factory = { webView }) }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFFFE2C55))) {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(selected = currentTab == 2, onClick = { currentTab = 2 }, icon = { Icon(Icons.Default.Settings, "设置") }, label = { Text("设置") })
                    NavigationBarItem(selected = currentTab == 3, onClick = { currentTab = 3 }, icon = { Icon(Icons.Default.BugReport, "调试") }, label = { Text("调试") })
                    NavigationBarItem(selected = currentTab == 1, onClick = { currentTab = 1 }, icon = { Icon(Icons.Default.History, "记录") }, label = { Text("记录") })
                    NavigationBarItem(selected = currentTab == 0, onClick = { currentTab = 0 }, icon = { Icon(Icons.Default.CloudDownload, "解析") }, label = { Text("解析") })
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when (currentTab) {
                    0 -> DownloaderTab(
                        inputValue = downloadInput,
                        onInputValueChange = { downloadInput = it },
                        status = status,
                        isRunning = isRunning,
                        selectedSource = selectedSource,
                        onSourceChange = { selectedSource = it },
                        keepWatermark = keepWatermark,
                        autoTriggerDownload = autoTriggerDownload,
                        onAutoTriggerReset = { autoTriggerDownload = false },
                        onStop = { isRunning = false; snifferUrl = null; status = "已手动停止" },
                        onKeepWatermarkChange = { keepWatermark = it },
                        onStartDownload = { url, baseName, origin ->
                            pendingBaseName = baseName
                            processingOrigin = origin
                            processedUrls.clear()
                            isRunning = true
                            status = "正在启动嗅探 ($selectedSource)..."
                            snifferUrl = url
                        }
                    )
                    1 -> HistoryTab(history = history, onRedownload = { item -> 
                        if (item.origin.isNotBlank()) {
                            downloadInput = item.origin
                            currentTab = 0
                            autoTriggerDownload = true
                            Toast.makeText(context, "正在重新解析下载...", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "无法重新解析：原链接丢失", Toast.LENGTH_LONG).show()
                        }
                    }, onRetryItem = { url, name, origin ->
                        startDownloadTask(url, name, origin)
                        Toast.makeText(context, "正在重新下载子任务...", Toast.LENGTH_SHORT).show()
                    })
                    2 -> SettingsTab(saveDir) { saveDir = it }
                    3 -> SimpleMediaDebugger(
                        history = history,
                        urlInput = debugUrlInput,
                        onUrlInputChange = { debugUrlInput = it },
                        sniffingUrl = debugSniffingUrl,
                        onSniffingUrlChange = { debugSniffingUrl = it },
                        debugStatus = debugStatusMessage,
                        onDebugStatusChange = { debugStatusMessage = it },
                        capturedUrls = debugCapturedUrls
                    )
                }
            }
        }
    }
}

@Composable
fun DownloaderTab(
    inputValue: String,
    onInputValueChange: (String) -> Unit,
    status: String,
    isRunning: Boolean,
    selectedSource: String,
    onSourceChange: (String) -> Unit,
    keepWatermark: Boolean,
    autoTriggerDownload: Boolean,
    onAutoTriggerReset: () -> Unit,
    onStop: () -> Unit,
    onKeepWatermarkChange: (Boolean) -> Unit,
    onStartDownload: (String, String, String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    LaunchedEffect(autoTriggerDownload) {
        if (autoTriggerDownload && inputValue.isNotBlank() && !isRunning) {
            val url = extractUrl(inputValue)
            val baseName = extractBaseFileName(inputValue)
            if (url != null) { onStartDownload(url, baseName, inputValue) }
            onAutoTriggerReset()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(scrollState)) {
        Text("专下 B站 / 抖音", fontSize = 24.sp, fontWeight = FontWeight.Black)
        Spacer(modifier = Modifier.height(24.dp))
        Text("1. 选择来源平台", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Bilibili", "Douyin", "Twitter").forEach { source ->
                val isSelected = selectedSource == source
                Surface(
                    modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(8.dp)).clickable { onSourceChange(source) },
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFFF5F5F5),
                    contentColor = if (isSelected) Color.White else Color.Black,
                    border = if (isSelected) null else BorderStroke(1.dp, Color.LightGray)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = when(source) {
                                "Bilibili" -> "哔哩哔哩"
                                "Douyin" -> "抖音视频"
                                else -> "Twitter/X"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("2. 粘贴分享链接", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = inputValue,
            onValueChange = onInputValueChange,
            placeholder = { 
                Text(
                    text = when(selectedSource) {
                        "Bilibili" -> "在此粘贴 哔哩哔哩 分享文本"
                        "Douyin" -> "在此粘贴 抖音 分享文本"
                        else -> "在此粘贴 Twitter/X 分享链接"
                    }
                ) 
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (selectedSource == "Douyin") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onKeepWatermarkChange(!keepWatermark) }) {
                Checkbox(checked = keepWatermark, onCheckedChange = onKeepWatermarkChange)
                Text("保留抖音原片水印", fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (isRunning) {
            Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("后台正在全力解析中...", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.width(12.dp))
                    TextButton(onClick = onStop) { Text("停止") }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(status, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = {
                val clipText = clipboard.getText()?.text?.toString() ?: ""
                val clipUrl = extractUrl(clipText)
                val inputUrl = extractUrl(inputValue)
                val useClipboard = clipText.isNotBlank() && (inputValue.isBlank() || (clipUrl != null && clipUrl != inputUrl))
                val inputToUse = if (useClipboard) { onInputValueChange(clipText); clipText } else { inputValue }
                if (inputToUse.isNotBlank()) {
                    val url = extractUrl(inputToUse)
                    val baseName = extractBaseFileName(inputToUse)
                    if (url != null) { onStartDownload(url, baseName, inputToUse) } else { Toast.makeText(context, "未检测到有效链接", Toast.LENGTH_SHORT).show() }
                } else { Toast.makeText(context, "内容为空", Toast.LENGTH_SHORT).show() }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.ContentPaste, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("立即解析并下载", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun HistoryTab(history: List<DownloadHistory>, onRedownload: (DownloadHistory) -> Unit, onRetryItem: (String, String, String) -> Unit) {
    val playingVideoPath = remember { mutableStateOf<String?>(null) }
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val displayList = remember(history.toList()) {
        val processed = mutableListOf<Any>()
        val (playable, invalid) = history.partition { it.status == 0 || it.status == 1 }
        val playableGroups = playable.groupBy { 
            val extracted = if (it.origin.isNotBlank()) extractUrl(it.origin) else null
            extracted ?: it.url 
        }
        val addedKeys = mutableSetOf<String>()
        playable.forEach { item ->
            val extracted = if (item.origin.isNotBlank()) extractUrl(item.origin) else null
            val key = extracted ?: item.url
            if (!addedKeys.contains(key)) {
                val group = playableGroups[key] ?: emptyList()
                if (group.size > 1) { processed.add(group) } else { processed.add(item) }
                addedKeys.add(key)
            }
        }
        if (invalid.isNotEmpty()) {
            processed.add("INVALID_SECTION_HEADER")
            processed.add(invalid.sortedByDescending { it.time }) 
        }
        processed
    }

    if (playingVideoPath.value != null) {
        val path = playingVideoPath.value!!
        val isImage = path.endsWith(".webp") || path.endsWith(".jpg") || path.endsWith(".png") || path.endsWith(".gif")
        AlertDialog(
            onDismissRequest = { playingVideoPath.value = null },
            confirmButton = { Button(onClick = { playingVideoPath.value = null }) { Text("关闭") } },
            text = {
                AndroidView(factory = { ctx ->
                    if (isImage) { android.widget.ImageView(ctx).apply { adjustViewBounds = true; scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; setImageURI(Uri.parse(path)) } }
                    else { android.widget.VideoView(ctx).apply { setVideoPath(path); start(); setOnCompletionListener { start() } } }
                }, modifier = Modifier.fillMaxWidth().height(400.dp))
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("历史记录", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(modifier = Modifier.weight(1f).lazyVerticalScrollbar(listState), state = listState) {
            items(displayList) { item ->
                if (item is String && item == "INVALID_SECTION_HEADER") {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("已过滤的无效/失败资源", color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                } else if (item is List<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val groupItems = item as List<DownloadHistory>
                    val firstItem = groupItems.first()
                    val isInvalidGroup = firstItem.status == 2 || firstItem.status == 3
                    val groupKey = if (isInvalidGroup) "invalid_archive" else { (if (firstItem.origin.isNotBlank()) extractUrl(firstItem.origin) else null) ?: firstItem.url }
                    val isExpanded = expandedGroups[groupKey] == true
                    val downloadingCount = groupItems.count { it.status == 0 }
                    val statusLabel = if (isInvalidGroup) "无效资源" else if (downloadingCount > 0) "正在下载 ($downloadingCount/${groupItems.size})" else "已完成 (${groupItems.size})"
                    val themeColor = if (isInvalidGroup) Color(0xFF9E9E9E) else if (downloadingCount > 0) Color(0xFF2196F3) else Color(0xFF4CAF50)

                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(if (isInvalidGroup) Color(0xFFF5F5F5) else if (downloadingCount > 0) Color(0xFFE3F2FD) else Color(0xFFE8F5E9))) {
                        Row(modifier = Modifier.fillMaxWidth().clickable { expandedGroups[groupKey] = !isExpanded }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, "Expand", tint = themeColor)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = if (isInvalidGroup) "查看所有无效资源 (${groupItems.size} 个)" else "$statusLabel: ${firstItem.name.substringBeforeLast("_")}", color = themeColor, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                                Text("来源: ...${((if (firstItem.origin.isNotBlank()) extractUrl(firstItem.origin) else null) ?: firstItem.url).takeLast(25)}", fontSize = 10.sp, color = Color.Gray)
                            }
                            if (!isInvalidGroup) {
                                IconButton(onClick = { onRedownload(firstItem) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Refresh, "Re-sniff All", tint = themeColor, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        if (isExpanded) {
                            groupItems.forEach { subItem ->
                                Divider(color = Color.White.copy(alpha = 0.5f), thickness = 1.dp)
                                Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f).clickable { if (subItem.status == 1) playingVideoPath.value = subItem.path }) {
                                        Text(text = subItem.name, fontSize = 12.sp, color = if (subItem.status == 0) Color.Blue else if (subItem.status == 1) Color.Black else Color.Gray, maxLines = 1)
                                        if (subItem.status == 0) LinearProgressIndicator(progress = if(subItem.progress >= 0) subItem.progress else 0.5f, modifier = Modifier.fillMaxWidth().height(2.dp), color = themeColor)
                                        Text(java.text.SimpleDateFormat("MM-dd HH:mm").format(subItem.time), fontSize = 9.sp, color = Color.Gray)
                                    }
                                    if (subItem.status == 1) IconButton(onClick = { playingVideoPath.value = subItem.path }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.PlayArrow, "Preview", modifier = Modifier.size(18.dp), tint = themeColor) }
                                    IconButton(onClick = { 
                                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(subItem.url))
                                        Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show()
                                    }, modifier = Modifier.size(32.dp)) { 
                                        Icon(Icons.Default.ContentCopy, "Copy", tint = themeColor, modifier = Modifier.size(18.dp)) 
                                    }
                                    IconButton(onClick = { onRetryItem(subItem.url, subItem.name.substringBeforeLast("_"), subItem.origin) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Refresh, "Retry", tint = themeColor, modifier = Modifier.size(18.dp)) }
                                }
                            }
                        }
                    }
                } else if (item is DownloadHistory) {
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f).clickable { if (item.status == 1) playingVideoPath.value = item.path }) {
                            Text(item.name, color = if (item.status == 1) Color.Black else if (item.status == 0) Color.Blue else Color.Red)
                            Text(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(item.time), fontSize = 11.sp, color = Color.Gray)
                            if (item.status == 0) LinearProgressIndicator(progress = if(item.progress >= 0) item.progress else 0.5f, modifier = Modifier.fillMaxWidth().height(4.dp))
                        }
                        IconButton(onClick = { 
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(item.url))
                            Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.size(32.dp)) { 
                            Icon(Icons.Default.ContentCopy, "Copy", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) 
                        }
                        IconButton(onClick = { onRedownload(item) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Refresh, "Retry", tint = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsTab(dir: String, onUpdate: (String) -> Unit) {
    val scrollState = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(scrollState)) {
        Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.height(24.dp))
        Text("存储路径: /Download/${dir}/"); OutlinedTextField(value = dir, onValueChange = onUpdate, modifier = Modifier.fillMaxWidth())
    }
}

fun extractUrl(s: String): String? {
    val m = Pattern.compile("https?://[^\\s]+").matcher(s)
    return if (m.find()) m.group() else null
}

fun extractBaseFileName(text: String): String {
    var temp = text.replace(Regex("https?://[^\\s]+"), "")
    temp = temp.replace(Regex(".*?复制打开抖音，看看"), "").replace(Regex(".*?v\\.douyin\\.com/.*?"), "")
    val bracketMatch = Regex("【(.*?)】").find(temp)
    if (bracketMatch != null) temp = bracketMatch.groupValues[1]
    temp = temp.replace(Regex("#[^\\s#]+"), "").replace(Regex("[0-9]+\\.[0-9]+ [a-zA-Z]+/"), "").trim()
    return if (temp.isNotBlank()) temp.replace(Regex("""[\\/:*?"<>|]"""), "_") else "Media"
}

suspend fun performDownload(ctx: Context, url: String, baseName: String, dir: String, ua: String, onProgress: (Float) -> Unit = {}): String? {
    return withContext(Dispatchers.IO) {
        var response: okhttp3.Response? = null
        try {
            val referer = when {
                url.contains("douyin.com") || url.contains("iesdouyin.com") -> "https://www.douyin.com/"
                url.contains("bilibili.com") || url.contains("bilivideo.com") || url.contains("hdslb.com") -> "https://www.bilibili.com/"
                url.contains("twitter.com") || url.contains("x.com") || url.contains("twimg.com") -> "https://x.com/"
                else -> url
            }
            val cookie = CookieManager.getInstance().getCookie(url)
            val reqBuilder = Request.Builder().url(url).header("User-Agent", ua).header("Referer", referer).header("Range", "bytes=0-").header("Connection", "keep-alive")
            if (!cookie.isNullOrBlank()) reqBuilder.header("Cookie", cookie)
            response = globalOkHttpClient.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) return@withContext null
            val contentType = response.header("Content-Type") ?: ""
            val body = response.body ?: return@withContext null
            val total = body.contentLength()
            if (contentType.contains("text/html") && total < 100 * 1024) return@withContext null
            if (url.contains("hdslb.com")) {
                if (total > 0 && total < 50 * 1024) return@withContext null
                if (url.contains("/bfs/archive/") || url.contains("/bfs/cover/") || url.contains("/bfs/active/")) return@withContext null
            }
            if (contentType.contains("image/png")) return@withContext null
            val ext = when {
                contentType.contains("video") || url.contains(".mp4", true) -> ".mp4"
                contentType.contains("image/gif") || url.contains(".gif", true) -> ".gif"
                contentType.contains("image/jpeg") || url.contains(".jpg", true) -> ".jpg"
                contentType.contains("image/webp") || url.contains(".webp", true) -> ".webp"
                else -> ".mp4"
            }
            val finalName = "${baseName}_${System.currentTimeMillis() % 10000}$ext"
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), dir)
            if (!folder.exists()) folder.mkdirs()
            val file = File(folder, finalName)
            body.byteStream().use { input -> FileOutputStream(file).use { output -> 
                val buffer = ByteArray(8 * 1024); var bytesRead: Int; var totalRead = 0L; var lastUpdate = 0L
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead); totalRead += bytesRead
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 500) { lastUpdate = now; if (total > 0) onProgress(totalRead.toFloat() / total) else onProgress(-1f) }
                }
                output.flush()
            }}
            MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), null, null)
            file.absolutePath
        } catch (e: Exception) { null } finally { response?.close() }
    }
}

fun saveHistory(ctx: Context, list: List<DownloadHistory>) {
    try {
        val jsonArray = JSONArray()
        list.forEach {
            val obj = JSONObject()
            obj.put("name", it.name); obj.put("path", it.path); obj.put("time", it.time); obj.put("status", it.status); obj.put("url", it.url); obj.put("origin", it.origin)
            jsonArray.put(obj)
        }
        ctx.getSharedPreferences("videodown_prefs", Context.MODE_PRIVATE).edit().putString("history_json", jsonArray.toString()).apply()
    } catch (e: Exception) {}
}

fun loadHistory(ctx: Context): List<DownloadHistory> {
    val list = mutableListOf<DownloadHistory>()
    try {
        val jsonString = ctx.getSharedPreferences("videodown_prefs", Context.MODE_PRIVATE).getString("history_json", "[]") ?: "[]"
        val array = JSONArray(jsonString)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            var status = if (obj.has("status")) obj.getInt("status") else if (obj.optBoolean("isSuccess", false)) 1 else 2
            if (status == 0) status = 2
            list.add(DownloadHistory(obj.getString("name"), obj.getString("path"), obj.getLong("time"), status, obj.optString("url", ""), obj.optString("origin", ""), if (status == 1 || status == 3) 1f else 0f))
        }
    } catch (e: Exception) {}
    return list
}

@Composable
fun SimpleMediaDebugger(history: MutableList<DownloadHistory>, urlInput: String, onUrlInputChange: (String) -> Unit, sniffingUrl: String, onSniffingUrlChange: (String) -> Unit, debugStatus: String, onDebugStatusChange: (String) -> Unit, capturedUrls: SnapshotStateList<String>) {
    val clipboard = LocalClipboardManager.current; val context = LocalContext.current; val scope = rememberCoroutineScope()
    var previewUrl by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val expandedSuffixes = remember { mutableStateMapOf<String, Boolean>() }
    var selectedSource by remember { mutableStateOf("Bilibili") }
    
    val targetUA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

    if (previewUrl != null) {
        // ... (保持现有 AlertDialog 逻辑不变)
        AlertDialog(onDismissRequest = { previewUrl = null }, confirmButton = { Row {
            Button(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(previewUrl!!)); Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show() }) { Text("复制链接") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                val urlToDownload = previewUrl!!; onDebugStatusChange("正在下载资源...")
                scope.launch {
                    val res = performDownload(context, urlToDownload, "debug_" + extractBaseFileName(urlInput), "videodownDebug", targetUA)
                    if (res != null) { history.add(0, DownloadHistory(File(res).name, res, System.currentTimeMillis(), 1, urlToDownload)); withContext(Dispatchers.IO) { saveHistory(context, history) }; onDebugStatusChange("✅ 下载成功") }
                    else { onDebugStatusChange("❌ 下载失败") }
                }
                previewUrl = null
            }) { Text("下载") }
        }}, dismissButton = { Button(onClick = { previewUrl = null }) { Text("关闭") } }, text = { Box(modifier = Modifier.fillMaxWidth().height(400.dp)) { AndroidView(factory = { ctx -> WebView(ctx).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true; webViewClient = WebViewClient(); loadUrl(previewUrl!!) } }, modifier = Modifier.fillMaxSize()) } })
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("资源抓包调试器", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Bilibili", "Douyin", "Twitter").forEach { source ->
                val isSelected = selectedSource == source
                Surface(
                    modifier = Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(8.dp)).clickable { selectedSource = source },
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFFF5F5F5),
                    contentColor = if (isSelected) Color.White else Color.Black,
                    border = if (isSelected) null else BorderStroke(1.dp, Color.LightGray)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = if(source == "Bilibili") "B站" else if(source == "Douyin") "抖音" else "Twitter", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        OutlinedTextField(value = urlInput, onValueChange = onUrlInputChange, label = { Text("粘贴待调试的链接") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { val clipText = clipboard.getText()?.text ?: ""; onUrlInputChange(clipText.toString()); val extracted = extractUrl(clipText.toString()); if (!extracted.isNullOrBlank()) { capturedUrls.clear(); onSniffingUrlChange(extracted); onDebugStatusChange("正在嗅探: $extracted") } }, modifier = Modifier.fillMaxWidth()) { Text("粘贴并开始抓包") }
        Spacer(modifier = Modifier.height(8.dp)); Text(debugStatus, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        
        val groupedUrls = remember(capturedUrls.toList()) {
            capturedUrls.groupBy { url ->
                try {
                    val path = if (url.contains("?")) url.substringBefore("?") else url
                    val dotIndex = path.lastIndexOf('.')
                    if (dotIndex != -1 && dotIndex < path.length - 1) {
                         val ext = path.substring(dotIndex + 1).lowercase()
                         if (ext.length <= 5 && ext.all { it.isLetterOrDigit() }) ext else "other"
                    } else "other"
                } catch (e: Exception) { "other" }
            }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).background(Color(0xFFF5F5F5)).padding(8.dp).lazyVerticalScrollbar(listState), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groupedUrls.forEach { (suffix, urls) ->
                item {
                    val isExpanded = expandedSuffixes[suffix] ?: false
                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White)) {
                         Row(modifier = Modifier.fillMaxWidth().clickable { expandedSuffixes[suffix] = !isExpanded }.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                             Text(text = ".$suffix (${urls.size})", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                             Icon(if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, "Expand")
                         }
                    }
                }
                if (expandedSuffixes[suffix] == true) {
                    items(urls) { url ->
                        Column(modifier = Modifier.fillMaxWidth().background(Color.White)) {
                            Box(modifier = Modifier.fillMaxWidth().clickable { previewUrl = url }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(text = url, fontSize = 10.sp, color = if (url.contains(".mp4") || url.contains("play")) Color.Blue else Color.DarkGray)
                            }
                            Divider(color = Color.LightGray.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 12.dp))
                        }
                    }
                }
            }
        }
        
        Box(modifier = Modifier.size(1.dp).alpha(0f)) { 
            if (sniffingUrl.isNotEmpty()) { 
                key(sniffingUrl) { 
                    AndroidView(factory = { ctx -> 
                        WebView(ctx).apply { 
                            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                            settings.userAgentString = targetUA
                            addJavascriptInterface(object {
                                @JavascriptInterface
                                fun processJson(json: String) {
                                    scope.launch(Dispatchers.Main) {
                                        try {
                                            val urlPattern = Pattern.compile("https?://video\\.twimg\\.com/[^\"\\s]+\\.mp4[^\"\\s]*")
                                            val unescapedJson = json.replace("\\/", "/")
                                            val urlMatcher = urlPattern.matcher(unescapedJson)
                                            while (urlMatcher.find()) {
                                                val url = urlMatcher.group()
                                                if (!capturedUrls.contains(url)) capturedUrls.add(0, url)
                                            }
                                        } catch (e: Exception) {}
                                    }
                                }

                                @JavascriptInterface
                                fun processVideo(url: String) {
                                    scope.launch(Dispatchers.Main) {
                                        var finalMediaUrl = url
                                        if (finalMediaUrl.contains("hdslb.com") && finalMediaUrl.contains("@")) finalMediaUrl = finalMediaUrl.substringBefore("@")
                                        if (finalMediaUrl.contains("pbs.twimg.com/media/")) {
                                            if (finalMediaUrl.contains("?format=")) {
                                                if (!finalMediaUrl.contains("&name=orig")) finalMediaUrl = finalMediaUrl.substringBefore("&name=") + "&name=orig"
                                            } else if (!finalMediaUrl.contains(":orig") && !finalMediaUrl.contains("?")) finalMediaUrl = "$finalMediaUrl:orig"
                                        }
                                        if (!capturedUrls.contains(finalMediaUrl)) capturedUrls.add(0, finalMediaUrl)
                                    }
                                }
                            }, "SnifferBridge")

                            webViewClient = object : WebViewClient() { 
                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    onDebugStatusChange("正在加载页面...")
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    onDebugStatusChange("页面加载完成，等待嗅探...")
                                    val js = """
                                        (function() {
                                            var oldXHR = window.XMLHttpRequest;
                                            function newXHR() {
                                                var realXHR = new oldXHR();
                                                realXHR.addEventListener('readystatechange', function() {
                                                    if(realXHR.readyState == 4 && realXHR.status == 200) {
                                                        var responseUrl = realXHR.responseURL || '';
                                                        if (responseUrl.includes('TweetDetail') || responseUrl.includes('TweetResultByRestId')) {
                                                            SnifferBridge.processJson(realXHR.responseText);
                                                        }
                                                    }
                                                }, false);
                                                return realXHR;
                                            }
                                            window.XMLHttpRequest = newXHR;

                                            var oldFetch = window.fetch;
                                            window.fetch = function() {
                                                return oldFetch.apply(this, arguments).then(function(response) {
                                                    var responseUrl = response.url || '';
                                                    if (responseUrl.includes('TweetDetail') || responseUrl.includes('TweetResultByRestId')) {
                                                        var clonedResponse = response.clone();
                                                        clonedResponse.text().then(function(text) { SnifferBridge.processJson(text); });
                                                    }
                                                    return response;
                                                });
                                            };

                                            setInterval(function() {
                                                var videos = document.getElementsByTagName('video');
                                                for (var i = 0; i < videos.length; i++) {
                                                    var src = videos[i].currentSrc || videos[i].src;
                                                    if (src) SnifferBridge.processVideo(src);
                                                }
                                                var images = document.getElementsByTagName('img');
                                                for(var i=0; i<images.length; i++) {
                                                    var src = images[i].src;
                                                    if (src && (images[i].naturalWidth > 300)) {
                                                        SnifferBridge.processVideo(src);
                                                    }
                                                }
                                            }, 2000);
                                        })();
                                    """.trimIndent()
                                    view?.evaluateJavascript(js, null)
                                }

                                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                    super.onReceivedError(view, request, error)
                                    if (request?.isForMainFrame == true) {
                                        onDebugStatusChange("加载失败: ${error?.description}")
                                    }
                                }

                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? { 
                                    val u = request?.url?.toString() ?: ""; 
                                    post { 
                                        if (!capturedUrls.contains(u)) {
                                            capturedUrls.add(0, u)
                                            // Update status when a media-like resource is found
                                            if (u.contains(".mp4") || u.contains("video") || u.contains(".m3u8")) {
                                                onDebugStatusChange("已发现媒体资源!")
                                            }
                                        }
                                    }
                                    return super.shouldInterceptRequest(view, request) 
                                } 
                            }; 
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            loadUrl(sniffingUrl) 
                        } 
                    }) 
                } 
            } 
        }
    }
}
