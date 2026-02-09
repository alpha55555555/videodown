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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import java.io.File
import java.io.FileOutputStream
import java.util.regex.Pattern
import java.util.concurrent.TimeUnit

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
    val status: Int, // 0: Downloading, 1: Success, 2: Failed
    val url: String,
    val origin: String = "", // Original Share Link
    val progress: Float = 0f
)

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
    var currentTab by remember { mutableStateOf(0) }
    val history = remember { mutableStateListOf<DownloadHistory>() }
    var saveDir by remember { mutableStateOf("videodownData") }

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
        // Removed: if (shouldStopSniffer) { isRunning = false; snifferUrl = null }
        // Let the Auto-Stop Monitor handle stopping to allow multi-resource capture
        status = "任务已添加"

        scope.launch {
            val tempName = "${baseName}...".replace("Media...", "Media...")
            val newItem = DownloadHistory(tempName, "", System.currentTimeMillis(), 0, url, originUrl, 0f)
            history.add(0, newItem)
            
            try {
                // performDownload handles extension detection
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
                        history[index] = newItem.copy(name = savedName, path = res, status = 1, progress = 1f)
                        status = "✅ 下载成功: $savedName"
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
                // Increased timeout to 8 seconds to allow for scrolling/loading
                if (processedUrls.isNotEmpty() && (System.currentTimeMillis() - lastActivityTime > 8000)) {
                    isRunning = false
                    snifferUrl = null
                    status = "✅ 自动完成 (无新内容)"
                }
            }
        }
    }

    // Hidden Sniffer WebView (Always active if snifferUrl is set)
    if (snifferUrl != null) {
        val currentContext = LocalContext.current
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
                                    val finalMediaUrl = if (url.contains("douyin.com") && !keepWatermark) url.replace("playwm", "play") else url
                                    
                                    if (processedUrls.contains(finalMediaUrl)) return@launch
                                    processedUrls.add(finalMediaUrl)
                                    lastActivityTime = System.currentTimeMillis()

                                    status = "已抓取: ...${finalMediaUrl.takeLast(15)}"
                                    
                                    startDownloadTask(finalMediaUrl, pendingBaseName, processingOrigin)
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
                                    
                                    // Strict filtering: Large images only, no avatars/icons
                                    if (src && width > 350 && height > 350) {
                                        if (!src.includes('avatar') && 
                                            !src.includes('emoji') && 
                                            !src.includes('icon') && 
                                            !src.includes('logo') && 
                                            !src.includes('brand')) {
                                            SnifferBridge.processVideo(src);
                                        }
                                    }
                                }

                                // 1. Scan Videos (Always safe)
                                var videos = document.getElementsByTagName('video');
                                for (var i = 0; i < videos.length; i++) {
                                    var src = videos[i].currentSrc || videos[i].src;
                                    if (src) SnifferBridge.processVideo(src);
                                }

                                // 2. Identify Horizontal Scrollers (The Gallery)
                                var potentialGalleries = [];
                                var allElements = document.querySelectorAll('div, ul');
                                for(var i=0; i<allElements.length; i++) {
                                    var el = allElements[i];
                                    // Criteria: Horizontal overflow (content wider than box) AND Box is substantial size
                                    // This targets the image carousel but ignores small icon bars
                                    if ((el.scrollWidth > el.clientWidth + 20) && el.clientWidth > 250 && el.clientHeight > 300) {
                                         potentialGalleries.push(el);
                                    }
                                }

                                if (potentialGalleries.length > 0) {
                                    // Found Gallery! Focus scrolling and scanning here.
                                    for(var i=0; i<potentialGalleries.length; i++) {
                                        var el = potentialGalleries[i];
                                        el.scrollLeft += 300; // Scroll right to trigger lazy load
                                        
                                        // Scan images ONLY inside this gallery
                                        var images = el.getElementsByTagName('img');
                                        for(var j=0; j<images.length; j++) {
                                             processImg(images[j]);
                                        }
                                    }
                                } else {
                                    // No Gallery found (Single Image or Video). 
                                    // Scroll down A LITTLE BIT just to ensure main content loaded, but not comments.
                                    if (window.scrollY < 200) window.scrollBy(0, 20);
                                    
                                    var images = document.getElementsByTagName('img');
                                    for(var i=0; i<images.length; i++) {
                                        // Filter by vertical position to avoid comments at bottom
                                        // Only take images in the first 1.5 screens height
                                        var rect = images[i].getBoundingClientRect();
                                        if (rect.top < window.innerHeight * 1.5) { 
                                            processImg(images[i]);
                                        }
                                    }
                                }
                            }, 2000);
                        """.trimIndent()
                        view?.evaluateJavascript(js, null)
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val u = request?.url?.toString() ?: ""
                        // Check for Video OR Image
                        val isVideo = u.contains("aweme/v1/play/") || u.contains("video_id") || (u.contains(".mp4") && !u.contains(".js")) || (u.contains("video.twimg.com") && u.contains(".mp4"))
                        // Strict Image Filter (No PNG)
                        val isImage = (u.contains("douyinpic.com") || u.matches(Regex(".*\\.(jpg|jpeg|webp|gif).*"))) && 
                                      !u.contains(".png") &&
                                      !u.contains("avatar") && 
                                      !u.contains("emoji") && 
                                      !u.contains("icon") && 
                                      !u.contains("logo") && 
                                      !u.contains(".js") && 
                                      !u.contains(".css")
                        
                        if (isVideo || isImage) {
                            view?.post {
                                if (isRunning) {
                                    val finalMediaUrl = if (u.contains("douyin.com") && !keepWatermark) u.replace("playwm", "play") else u
                                    
                                    if (!processedUrls.contains(finalMediaUrl)) {
                                        processedUrls.add(finalMediaUrl)
                                        lastActivityTime = System.currentTimeMillis()
                                        
                                        startDownloadTask(finalMediaUrl, pendingBaseName, processingOrigin)
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

        Box(modifier = Modifier.size(0.dp)) {
            AndroidView(factory = { webView })
        }
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
                        keepWatermark = keepWatermark,
                        autoTriggerDownload = autoTriggerDownload,
                        onAutoTriggerReset = { autoTriggerDownload = false },
                        onStop = { 
                            isRunning = false 
                            snifferUrl = null
                            status = "已手动停止"
                        },
                        onKeepWatermarkChange = { keepWatermark = it },
                        onStartDownload = { url, baseName, origin ->
                            pendingBaseName = baseName
                            processingOrigin = origin
                            processedUrls.clear()
                            isRunning = true
                            if (url.contains("twitter.com") || url.contains("x.com")) {
                                status = "正在通过 API 解析 Twitter..."
                                scope.launch {
                                    val mediaUrl = fetchTwitterVideo(url)
                                    if (mediaUrl != null) {
                                        status = "正在下载原片..."
                                        startDownloadTask(mediaUrl, baseName, origin)
                                    } else {
                                        snifferUrl = url
                                        status = "API 失败，切换网页嗅探..."
                                    }
                                }
                            } else {
                                status = "正在启动网页嗅探..."
                                snifferUrl = url
                            }
                        }
                    )
                    1 -> HistoryTab(
                        history = history, 
                        onRedownload = { item -> 
                             if (item.origin.isNotBlank()) {
                                 // Smart Redownload: Go to parser and AUTO START
                                 downloadInput = item.origin
                                 currentTab = 0
                                 autoTriggerDownload = true
                                 Toast.makeText(context, "正在重新解析下载...", Toast.LENGTH_SHORT).show()
                             } else {
                                 // Prevent failing task creation for old items
                                 Toast.makeText(context, "无法重新下载：原链接丢失 (旧记录)", Toast.LENGTH_LONG).show()
                             }
                        }
                    )
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
    keepWatermark: Boolean,
    autoTriggerDownload: Boolean,
    onAutoTriggerReset: () -> Unit,
    onStop: () -> Unit,
    onKeepWatermarkChange: (Boolean) -> Unit,
    onStartDownload: (String, String, String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // Auto-trigger logic
    LaunchedEffect(autoTriggerDownload) {
        if (autoTriggerDownload && inputValue.isNotBlank() && !isRunning) {
            val url = extractUrl(inputValue)
            val baseName = extractBaseFileName(inputValue)
            if (url != null) {
                onStartDownload(url, baseName, inputValue)
            }
            onAutoTriggerReset()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("视频下载器", fontSize = 24.sp, fontWeight = FontWeight.Black)
        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFFEEEEEE))) {
            if (isRunning) {
                Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("后台处理中...", color = Color.Gray)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)) {
                        Text("停止 / 完成")
                    }
                }
            } else {
                Text("解析窗口 (空闲)", modifier = Modifier.align(Alignment.Center), color = Color.Gray, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(status, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(value = inputValue, onValueChange = onInputValueChange, label = { Text("粘贴 bilibili 或 抖音链接") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = keepWatermark, onCheckedChange = onKeepWatermarkChange)
            Text("保留原视频水印 (默认开启)")
        }
        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                val clipText = clipboard.getText()?.text?.toString() ?: ""
                val clipUrl = extractUrl(clipText)
                val inputUrl = extractUrl(inputValue)

                // Smart Logic: Use clipboard if input is blank OR clipboard has a NEW/DIFFERENT URL
                val useClipboard = clipText.isNotBlank() && (inputValue.isBlank() || (clipUrl != null && clipUrl != inputUrl))

                val inputToUse = if (useClipboard) {
                    onInputValueChange(clipText) // Update UI
                    clipText
                } else {
                    inputValue
                }

                if (inputToUse.isNotBlank()) {
                    val url = extractUrl(inputToUse)
                    val baseName = extractBaseFileName(inputToUse)
                    
                    if (url != null) {
                        onStartDownload(url, baseName, inputToUse)
                    } else {
                        Toast.makeText(context, "未检测到有效链接", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "内容为空", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("粘贴并解析下载")
        }
    }
}

@Composable
fun HistoryTab(history: List<DownloadHistory>, onRedownload: (DownloadHistory) -> Unit) {
    val context = LocalContext.current
    var playingVideoPath by remember { mutableStateOf<String?>(null) }
    // State to track expanded groups: Map<Url, Boolean>
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    // Logic to process history into display list
    val displayList = remember(history.toList()) {
        val processed = mutableListOf<Any>()
        // Group by combination of URL and Status to separate success/fail groups
        val groups = history.groupBy { "${it.url}_${it.status}" }
        val addedKeys = mutableSetOf<String>()
        
        history.forEach { item ->
            val key = "${item.url}_${item.status}"
            val group = groups[key] ?: emptyList()
            if (group.size > 1) {
                if (!addedKeys.contains(key)) {
                    processed.add(group) // Add the specific status group
                    addedKeys.add(key)
                }
            } else {
                processed.add(item)
            }
        }
        processed
    }

    if (playingVideoPath != null) {
        val isImage = playingVideoPath!!.endsWith(".webp") || playingVideoPath!!.endsWith(".jpg") || playingVideoPath!!.endsWith(".png") || playingVideoPath!!.endsWith(".gif")
        
        AlertDialog(
            onDismissRequest = { playingVideoPath = null },
            confirmButton = { Button(onClick = { playingVideoPath = null }) { Text("关闭") } },
            text = {
                AndroidView(
                    factory = { ctx ->
                        if (isImage) {
                            android.widget.ImageView(ctx).apply {
                                adjustViewBounds = true
                                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                                setImageURI(Uri.parse(playingVideoPath))
                            }
                        } else {
                            android.widget.VideoView(ctx).apply {
                                setVideoPath(playingVideoPath)
                                start()
                                setOnCompletionListener { start() } // Loop
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(400.dp)
                )
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("历史记录", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn {
            items(displayList) { item ->
                if (item is List<*>) {
                    // Group of multiple items for one URL and SAME status
                    val groupItems = item as List<DownloadHistory>
                    val firstItem = groupItems.first()
                    // Unique key for expansion state based on URL and Status
                    val groupKey = "${firstItem.url}_${firstItem.status}"
                    val isExpanded = expandedGroups[groupKey] == true
                    
                    val statusLabel = when(firstItem.status) {
                        1 -> "下载成功"
                        2 -> "下载失败"
                        else -> "正在下载"
                    }
                    val cardBg = when(firstItem.status) {
                        1 -> Color(0xFFE8F5E9) // Light Green
                        2 -> Color(0xFFFFEBEE) // Light Red
                        else -> Color(0xFFE3F2FD) // Light Blue
                    }
                    val themeColor = when(firstItem.status) {
                        1 -> Color(0xFF4CAF50)
                        2 -> Color(0xFFF44336)
                        else -> Color(0xFF2196F3)
                    }

                    Column(modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(cardBg)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedGroups[groupKey] = !isExpanded }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, 
                                "Expand",
                                tint = themeColor
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "$statusLabel (${groupItems.size} 个任务)",
                                    color = themeColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text("来源: ...${firstItem.url.takeLast(25)}", fontSize = 10.sp, color = Color.Gray)
                            }
                        }
                        
                        if (isExpanded) {
                            groupItems.forEach { subItem ->
                                Divider(color = Color.White.copy(alpha = 0.5f), thickness = 1.dp)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).clickable {
                                        if (subItem.status == 1) playingVideoPath = subItem.path
                                    }) {
                                        Text(
                                            text = subItem.name, 
                                            fontSize = 12.sp, 
                                            color = when(subItem.status) {
                                                0 -> Color.Blue
                                                1 -> Color.Black
                                                else -> Color.Red
                                            },
                                            maxLines = 1
                                        )
                                        if (subItem.status == 0) {
                                            if (subItem.progress >= 0f) {
                                                LinearProgressIndicator(progress = subItem.progress, modifier = Modifier.fillMaxWidth().height(2.dp), color = themeColor)
                                            } else {
                                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = themeColor)
                                            }
                                        }
                                        Text(java.text.SimpleDateFormat("MM-dd HH:mm").format(subItem.time), fontSize = 9.sp, color = Color.Gray)
                                    }
                                    
                                    if (subItem.status == 1) {
                                        IconButton(onClick = { playingVideoPath = subItem.path }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.PlayArrow, "Preview", modifier = Modifier.size(18.dp), tint = themeColor)
                                        }
                                    }
                                    
                                    IconButton(onClick = { onRedownload(subItem) }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Refresh, "Retry", tint = themeColor, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                } else if (item is DownloadHistory) {
                    // Normal Single Item
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f).clickable { 
                            if (item.status == 1) playingVideoPath = item.path 
                        }) {
                            Text(item.name, color = if (item.status == 1) Color.Black else if (item.status == 0) Color.Blue else Color.Red)
                            Text(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(item.time), fontSize = 11.sp, color = Color.Gray)
                            if (item.status == 0) {
                                Text("下载中...", fontSize = 10.sp, color = Color.Blue)
                                if (item.progress >= 0f) {
                                    LinearProgressIndicator(progress = item.progress, modifier = Modifier.fillMaxWidth().height(4.dp))
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                                }
                            } else if (item.status == 2) {
                                Text("下载失败", fontSize = 10.sp, color = Color.Red)
                            }
                            if (item.origin.isNotBlank()) {
                                Text("来源: 可重新解析", fontSize = 9.sp, color = Color.Gray)
                            }
                        }
                        
                        if (item.status == 1) {
                             IconButton(onClick = { playingVideoPath = item.path }) {
                                 Icon(Icons.Default.PlayArrow, contentDescription = "预览")
                             }
                        }
                        
                        IconButton(onClick = { onRedownload(item) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "重新下载")
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE)))
                }
            }
        }
    }
}

@Composable
fun SettingsTab(dir: String, onUpdate: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))
        Text("存储路径: /Download/${dir}/")
        OutlinedTextField(value = dir, onValueChange = onUpdate, modifier = Modifier.fillMaxWidth())
    }
}

fun extractUrl(s: String): String? {
    val m = Pattern.compile("https?://[^\\s]+").matcher(s)
    return if (m.find()) m.group() else null
}

fun extractBaseFileName(text: String): String {
    var temp = text.replace(Regex("https?://.*?"), "")
    temp = temp.replace(Regex(".*?复制打开抖音，看看"), "")
    temp = temp.replace(Regex("【.*?】"), "")
    temp = temp.trim()
    temp = temp.replace(Regex("""[\\/:*?"<>|]"""), "_")
    return if (temp.isNotBlank()) temp else "Media"
}

fun extractFileName(text: String, ext: String = ".mp4"):
 String {
    val base = extractBaseFileName(text)
    val randomSuffix = (System.currentTimeMillis() % 10000).toString()
    return "${base}_$randomSuffix$ext"
}

suspend fun fetchTwitterVideo(url: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val api = url.replace("twitter.com", "api.vxtwitter.com").replace("x.com", "api.vxtwitter.com")
            val resp = globalOkHttpClient.newCall(Request.Builder().url(api).build()).execute()
            val json = JSONObject(resp.body?.string() ?: "")
            json.getJSONArray("media_urls").getString(0)
        } catch (e: Exception) { null }
    }
}

suspend fun performDownload(ctx: Context, url: String, baseName: String, dir: String, ua: String, onProgress: (Float) -> Unit = {}): String? {
    return withContext(Dispatchers.IO) {
        var response: okhttp3.Response? = null
        try {
            // 1. Determine dynamic Referer
            val referer = when {
                url.contains("douyin.com") || url.contains("iesdouyin.com") -> "https://www.douyin.com/"
                url.contains("bilibili.com") || url.contains("bilivideo.com") || url.contains("hdslb.com") -> "https://www.bilibili.com/"
                url.contains("twitter.com") || url.contains("x.com") || url.contains("twimg.com") -> "https://x.com/"
                else -> url
            }

            // 2. Sync Cookies from WebView
            val cookie = CookieManager.getInstance().getCookie(url)

            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Referer", referer)
                .header("Range", "bytes=0-") // Essential for many video CDNs
                .header("Connection", "keep-alive")

            if (!cookie.isNullOrBlank()) {
                reqBuilder.header("Cookie", cookie)
            }
            
            val req = reqBuilder.build()
            response = globalOkHttpClient.newCall(req).execute()
            
            // Handle cases where 200 or 206 (Partial Content) is returned
            if (!response.isSuccessful) {
                Log.e("videodown", "Download failed with code: ${response.code}")
                return@withContext null
            }
            
            val contentType = response.header("Content-Type") ?: ""
            val body = response.body ?: return@withContext null
            val total = body.contentLength()

            // Relaxed check: Only block small HTML files. Large ones might be mislabeled media.
            if (contentType.contains("text/html") && total < 100 * 1024) {
                Log.w("videodown", "Blocked small HTML file: $url")
                return@withContext null
            }
            
            // STRICTLY BLOCK PNG
            if (contentType.contains("image/png")) return@withContext null

            val ext = when {
                contentType.contains("video") || url.contains(".mp4", ignoreCase = true) -> ".mp4"
                contentType.contains("image/gif") || url.contains(".gif", ignoreCase = true) -> ".gif"
                contentType.contains("image/jpeg") || url.contains(".jpg", ignoreCase = true) -> ".jpg"
                contentType.contains("image/webp") || url.contains(".webp", ignoreCase = true) -> ".webp"
                else -> ".mp4" // Fallback
            }
            
            val finalName = "${baseName}_${System.currentTimeMillis() % 10000}$ext"
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), dir)
            if (!folder.exists()) folder.mkdirs()
            val file = File(folder, finalName)
            
            body.byteStream().use { input ->
                FileOutputStream(file).use { output -> 
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastProgressUpdate = 0L
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 500) { // Throttle updates: max 2 times/sec
                            lastProgressUpdate = now
                            if (total > 0) {
                                onProgress(totalRead.toFloat() / total)
                            } else {
                                onProgress(-1f) // Indeterminate
                            }
                        }
                    }
                    output.flush()
                }
            }
            
            // 通知相册扫描新文件
            ctx.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))
            file.absolutePath
        } catch (e: Exception) { 
            Log.e("videodown", "PerformDownload Error: ${e.message}", e)
            null 
        } finally {
            response?.close()
        }
    }
}

fun scanAndPlay(ctx: Context, path: String) {
    Toast.makeText(ctx, "请直接前往系统相册查看视频", Toast.LENGTH_LONG).show()
}

fun saveHistory(ctx: Context, list: List<DownloadHistory>) {
    try {
        val jsonArray = JSONArray()
        list.forEach {
            val obj = JSONObject()
            obj.put("name", it.name)
            obj.put("path", it.path)
            obj.put("time", it.time)
            obj.put("status", it.status)
            obj.put("url", it.url)
            obj.put("origin", it.origin)
            jsonArray.put(obj)
        }
        ctx.getSharedPreferences("videodown_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("history_json", jsonArray.toString())
            .apply()
    } catch (e: Exception) { e.printStackTrace() }
}

fun loadHistory(ctx: Context): List<DownloadHistory> {
    val list = mutableListOf<DownloadHistory>()
    try {
        val jsonString = ctx.getSharedPreferences("videodown_prefs", Context.MODE_PRIVATE)
            .getString("history_json", "[]") ?: "[]"
        val array = JSONArray(jsonString)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            // Migration logic
            var status = if (obj.has("status")) obj.getInt("status") 
                         else if (obj.optBoolean("isSuccess", false)) 1 else 2
            
            // Fix: If status is 0 (Downloading) on load, it means the app was killed/closed. 
            // Mark it as failed so it doesn't stay stuck.
            if (status == 0) {
                status = 2
            }

            val url = obj.optString("url", "")
            val origin = obj.optString("origin", "")
            
            list.add(DownloadHistory(
                obj.getString("name"),
                obj.getString("path"),
                obj.getLong("time"),
                status,
                url,
                origin,
                if (status == 1) 1f else 0f
            ))
        }
    } catch (e: Exception) { e.printStackTrace() }
    return list
}

@Composable
fun SimpleMediaDebugger(
    history: MutableList<DownloadHistory>,
    urlInput: String,
    onUrlInputChange: (String) -> Unit,
    sniffingUrl: String,
    onSniffingUrlChange: (String) -> Unit,
    debugStatus: String,
    onDebugStatusChange: (String) -> Unit,
    capturedUrls: SnapshotStateList<String>
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val saveDir = "videodownDebug" 
    val targetUA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

    var previewUrl by remember { mutableStateOf<String?>(null) }

    if (previewUrl != null) {
        AlertDialog(
            onDismissRequest = { previewUrl = null },
            confirmButton = {
                Row {
                    Button(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(previewUrl!!))
                        Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("复制链接")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        val urlToDownload = previewUrl!!
                        onDebugStatusChange("正在下载资源...")
                        scope.launch {
                            val baseName = "debug_" + extractBaseFileName(urlInput)
                            val res = performDownload(context, urlToDownload, baseName, saveDir, targetUA)
                            if (res != null) {
                                val savedName = File(res).name
                                history.add(0, DownloadHistory(savedName, res, System.currentTimeMillis(), 1, urlToDownload))
                                withContext(Dispatchers.IO) {
                                    saveHistory(context, history)
                                }
                                onDebugStatusChange("✅ 下载成功: $savedName")
                            } else {
                                onDebugStatusChange("❌ 下载失败")
                            }
                        }
                        previewUrl = null
                    }) {
                        Text("下载")
                    }
                }
            },
            dismissButton = {
                Button(onClick = { previewUrl = null }) {
                    Text("关闭")
                }
            },
            text = {
                Box(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.userAgentString = targetUA
                                webViewClient = WebViewClient()
                                this.loadUrl(previewUrl!!)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("资源抓包调试器", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        
        OutlinedTextField(
            value = urlInput,
            onValueChange = onUrlInputChange,
            label = { Text("粘贴 bilibili 或 抖音链接") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                val clipText = clipboard.getText()?.text ?: ""
                onUrlInputChange(clipText)
                val extracted = extractUrl(clipText)
                if (!extracted.isNullOrBlank()) {
                    capturedUrls.clear()
                    onSniffingUrlChange(extracted)
                    onDebugStatusChange("正在嗅探: $extracted")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("粘贴并开始抓包")
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(debugStatus, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        Text("捕获列表 (点击预览):", fontSize = 11.sp, color = Color.Gray)
        
        // 占据剩余空间的列表
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFFF5F5F5)),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(8.dp)
        ) {
            items(capturedUrls) { url ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
                        .clickable { previewUrl = url }
                        .padding(12.dp)
                ) {
                    Text(
                        text = url,
                        fontSize = 10.sp,
                        color = if (url.contains(".mp4") || url.contains("play")) Color.Blue else Color.DarkGray
                    )
                }
            }
        }

        // 隐藏的 WebView 用于抓包 (设置极小尺寸并透明，比 0.dp 更稳健)
        Box(modifier = Modifier.size(1.dp).alpha(0f)) {
            if (sniffingUrl.isNotEmpty()) {
                key(sniffingUrl) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.userAgentString = targetUA
                                webViewClient = object : WebViewClient() {
                                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                        val u = request?.url?.toString() ?: ""
                                        val isMedia = u.contains(".mp4") || u.contains(".webp") || u.contains(".gif") ||
                                                      u.contains("video") || u.contains("douyinpic") || 
                                                      u.contains("aweme/v1/play")
                                        if (isMedia) {
                                            post { if (!capturedUrls.contains(u)) capturedUrls.add(0, u) }
                                        }
                                        return super.shouldInterceptRequest(view, request)
                                    }
                                }
                                this.loadUrl(sniffingUrl)
                            }
                        }
                    )
                }
            }
        }
    }
}
