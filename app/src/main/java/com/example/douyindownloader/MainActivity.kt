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
    val length = size.height * (size.height / state.maxValue.toFloat().coerceAtLeast(size.height))
    val offset = (state.value.toFloat() / state.maxValue.toFloat().coerceAtLeast(1f)) * (size.height - length)
    drawRect(color = Color.Gray.copy(alpha = 0.5f), topLeft = Offset(size.width - 4.dp.toPx(), offset), size = Size(4.dp.toPx(), length))
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
    var selectedSource by remember { mutableStateOf("Bilibili") } // "Bilibili" or "Douyin"

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
    
    // Auto-Stop Monitor
    LaunchedEffect(isRunning) {
        if (isRunning) {
            lastActivityTime = System.currentTimeMillis()
            while (isActive && isRunning) {
                delay(1000)
                if (processedUrls.isNotEmpty() && (System.currentTimeMillis() - lastActivityTime > 8000)) {
                    isRunning = false
                    snifferUrl = null
                    status = "✅ 自动完成 (无新内容)"
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
                settings.userAgentString = targetUA
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun processVideo(url: String) {
                        if (isRunning && url.isNotEmpty() && !url.startsWith("blob:")) {
                            scope.launch(Dispatchers.Main) {
                                if (snifferUrl != null) {
                                    // Bilibili specific: Strip image processing suffix (e.g., @344w_194h_1c.webp) to get original image
                                    var finalMediaUrl = if (url.contains("hdslb.com") && url.contains("@")) {
                                        url.substringBefore("@")
                                    } else {
                                        url
                                    }

                                    if (finalMediaUrl.contains("douyin.com") && !keepWatermark) {
                                        finalMediaUrl = finalMediaUrl.replace("playwm", "play")
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
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return false
                        return !url.startsWith("http")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val js = """
                            setInterval(function() {
                                function processImg(img) {
                                    var src = img.src;
                                    var width = img.naturalWidth || img.width;
                                    var height = img.naturalHeight || img.height;
                                    if (src && width > 400 && height > 300) {
                                        if (!src.includes('avatar') && !src.includes('emoji') && 
                                            !src.includes('icon') && !src.includes('logo') && 
                                            !src.includes('face') && !src.includes('brand')) {
                                            SnifferBridge.processVideo(src);
                                        }
                                    }
                                }
                                var videos = document.getElementsByTagName('video');
                                for (var i = 0; i < videos.length; i++) {
                                    var src = videos[i].currentSrc || videos[i].src;
                                    if (src) SnifferBridge.processVideo(src);
                                }
                                var potentialGalleries = [];
                                var allElements = document.querySelectorAll('div, ul');
                                for(var i=0; i<allElements.length; i++) {
                                    var el = allElements[i];
                                    if ((el.scrollWidth > el.clientWidth + 20) && el.clientWidth > 250 && el.clientHeight > 300) {
                                         potentialGalleries.push(el);
                                    }
                                }
                                if (potentialGalleries.length > 0) {
                                    for(var i=0; i<potentialGalleries.length; i++) {
                                        var el = potentialGalleries[i];
                                        el.scrollLeft += 300;
                                        var images = el.getElementsByTagName('img');
                                        for(var j=0; j<images.length; j++) { processImg(images[j]); }
                                    }
                                } else {
                                    if (window.scrollY < 200) window.scrollBy(0, 20);
                                    var images = document.getElementsByTagName('img');
                                    for(var i=0; i<images.length; i++) {
                                        var rect = images[i].getBoundingClientRect();
                                        if (rect.top < window.innerHeight * 1.5) { processImg(images[i]); }
                                    }
                                }
                            }, 2000);
                        """.trimIndent()
                        view?.evaluateJavascript(js, null)
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val u = request?.url?.toString() ?: ""
                        val isVideo = u.contains("aweme/v1/play/") || u.contains("video_id") || u.contains(".mp4") || 
                                      u.contains("bilivideo.com") || (u.contains("video.twimg.com") && u.contains(".mp4"))
                        val isLargeImage = (u.contains("douyinpic.com") || u.contains("hdslb.com") || u.matches(Regex(".*\\.(jpg|jpeg|webp|gif).*"))) && 
                                           !u.contains(".png") && !u.contains("avatar") && !u.contains("emoji") && 
                                           !u.contains("icon") && !u.contains("logo") && !u.contains("face") && 
                                           !u.contains("banner") && !u.contains(".js") && !u.contains(".css") &&
                                           !u.contains("hdslb.com/bfs/archive/") && !u.contains("hdslb.com/bfs/cover/") &&
                                           !u.contains("hdslb.com/bfs/active/") && !u.contains("hdslb.com/bfs/article/")
                        val isNoise = u.contains("log-upload") || u.contains("analytics") || u.contains("ads") || u.contains("doubleclick")

                        if ((isVideo || isLargeImage) && !isNoise) {
                            view?.post {
                                if (isRunning) {
                                    var finalMediaUrl = if (u.contains("hdslb.com") && u.contains("@")) u.substringBefore("@") else u
                                    if (finalMediaUrl.contains("douyin.com") && !keepWatermark) finalMediaUrl = finalMediaUrl.replace("playwm", "play")
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(scrollState).verticalScrollbar(scrollState)) {
        Text("专下 B站 / 抖音", fontSize = 24.sp, fontWeight = FontWeight.Black)
        Spacer(modifier = Modifier.height(24.dp))
        Text("1. 选择来源平台", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Bilibili", "Douyin").forEach { source ->
                val isSelected = selectedSource == source
                Surface(
                    modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(8.dp)).clickable { onSourceChange(source) },
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFFF5F5F5),
                    contentColor = if (isSelected) Color.White else Color.Black,
                    border = if (isSelected) null else BorderStroke(1.dp, Color.LightGray)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(if(source == "Bilibili") "哔哩哔哩" else "抖音视频", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("2. 粘贴分享链接", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(value = inputValue, onValueChange = onInputValueChange, placeholder = { Text("在此粘贴 $selectedSource 分享文本") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
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
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(scrollState).verticalScrollbar(scrollState)) {
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
    val scrollState = rememberScrollState()
    if (previewUrl != null) {
        AlertDialog(onDismissRequest = { previewUrl = null }, confirmButton = { Row {
            Button(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(previewUrl!!)); Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show() }) { Text("复制链接") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                val urlToDownload = previewUrl!!; onDebugStatusChange("正在下载资源...")
                scope.launch {
                    val res = performDownload(context, urlToDownload, "debug_" + extractBaseFileName(urlInput), "videodownDebug", "Mozilla/5.0")
                    if (res != null) { history.add(0, DownloadHistory(File(res).name, res, System.currentTimeMillis(), 1, urlToDownload)); withContext(Dispatchers.IO) { saveHistory(context, history) }; onDebugStatusChange("✅ 下载成功") }
                    else { onDebugStatusChange("❌ 下载失败") }
                }
                previewUrl = null
            }) { Text("下载") }
        }}, dismissButton = { Button(onClick = { previewUrl = null }) { Text("关闭") } }, text = { Box(modifier = Modifier.fillMaxWidth().height(400.dp)) { AndroidView(factory = { ctx -> WebView(ctx).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true; webViewClient = WebViewClient(); loadUrl(previewUrl!!) } }, modifier = Modifier.fillMaxSize()) } })
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(scrollState).verticalScrollbar(scrollState)) {
        Text("资源抓包调试器", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = urlInput, onValueChange = onUrlInputChange, label = { Text("粘贴 bilibili 或 抖音链接") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { val clipText = clipboard.getText()?.text ?: ""; onUrlInputChange(clipText.toString()); val extracted = extractUrl(clipText.toString()); if (!extracted.isNullOrBlank()) { capturedUrls.clear(); onSniffingUrlChange(extracted); onDebugStatusChange("正在嗅探: $extracted") } }, modifier = Modifier.fillMaxWidth()) { Text("粘贴并开始抓包") }
        Spacer(modifier = Modifier.height(8.dp)); Text(debugStatus, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        
        Column(modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp).background(Color(0xFFF5F5F5)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            capturedUrls.forEach { url -> 
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, Color.LightGray, RoundedCornerShape(8.dp)).clickable { previewUrl = url }.padding(12.dp)) { 
                    Text(text = url, fontSize = 10.sp, color = if (url.contains(".mp4") || url.contains("play")) Color.Blue else Color.DarkGray) 
                } 
            }
        }
        
        Box(modifier = Modifier.size(1.dp).alpha(0f)) { 
            if (sniffingUrl.isNotEmpty()) { 
                key(sniffingUrl) { 
                    AndroidView(factory = { ctx -> 
                        WebView(ctx).apply { 
                            settings.javaScriptEnabled = true; settings.domStorageEnabled = true; webViewClient = object : WebViewClient() { 
                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? { 
                                    val u = request?.url?.toString() ?: ""; 
                                    val isBiliImage = u.contains("hdslb.com/bfs/archive/") || u.contains("hdslb.com/bfs/cover/") || u.contains("hdslb.com/bfs/active/")
                                    if ((u.contains(".mp4") || u.contains(".webp") || u.contains("video") || u.contains("aweme/v1/play")) && !isBiliImage) { 
                                        post { if (!capturedUrls.contains(u)) capturedUrls.add(0, u) } 
                                    }; return super.shouldInterceptRequest(view, request) 
                                } 
                            }; loadUrl(sniffingUrl) 
                        } 
                    }) 
                } 
            } 
        }
    }
}
