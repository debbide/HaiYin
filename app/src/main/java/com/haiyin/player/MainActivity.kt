package com.haiyin.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLogger.install(applicationContext)
        Player.init(applicationContext)
        Favorites.init(applicationContext)
        SourceScripts.init(applicationContext)
        Lyrics.init(applicationContext)
        CoverLoader.init(applicationContext)
        requestNotifPermissionIfNeeded()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = D.accent,
                    background = D.bg,
                    surface = D.surface,
                    surfaceVariant = D.surfaceVar,
                )
            ) {
                HaiYinApp()
            }
        }
    }

    /** Android 13+: POST_NOTIFICATIONS runtime check */
    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun HaiYinApp() {
        var tab by remember { mutableStateOf(0) }   // 0: 发现/搜索, 1: 收藏, 2: 导歌单, 3: 音源脚本
        var query by remember { mutableStateOf("") }
        var results by remember { mutableStateOf(listOf<Sources.Song>()) }
        var searching by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("输入歌曲或歌手，即点即播") }
        var quality by remember { mutableStateOf("320k") }
        var showCrashLog by remember { mutableStateOf(false) }
        var showFullPlayer by remember { mutableStateOf(false) }
        val favVersion by Favorites.version.collectAsState()
        var importText by remember { mutableStateOf("") }
        var importing by remember { mutableStateOf(false) }
        var scriptsVersion by remember { mutableStateOf(0) }
        val playerState by Player.state.collectAsState()
        val scope = rememberCoroutineScope()

        fun doSearch() {
            if (query.isNotBlank() && !searching) {
                searching = true
                status = "正在多源检索中…"
                tab = 0
                scope.launch {
                    val songs = withContext(Dispatchers.IO) {
                        try { Sources.searchAll(query.trim()) } catch (_: Exception) { emptyList() }
                    }
                    results = songs
                    searching = false
                    status = if (songs.isEmpty()) "未找到相关歌曲，换个词试试" else "检索到 ${songs.size} 首歌曲 · 点击即播"
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(D.bg)
                .systemBarsPadding()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 1. Top Header Bar (Title, Version, Quality Chips)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("海音", fontSize = 26.sp, fontWeight = FontWeight.Black, color = D.accent)
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(D.surfaceBorder)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { showCrashLog = !showCrashLog }
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "v" + BuildConfig.VERSION_NAME,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = D.textDim
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    // Audio Quality Selector Chips
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("128k" to "128", "320k" to "320", "flac" to "FLAC").forEach { (q, label) ->
                            SelectChip(label, quality == q) {
                                quality = q
                                Player.quality = q
                            }
                        }
                    }
                }

                // Crash Log Debug Card (Long-press version to toggle)
                if (showCrashLog) {
                    val crash = remember { CrashLogger.latestCrashLog() }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(D.surfaceVar)
                            .border(1.dp, D.surfaceBorder, RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            crash?.take(1200) ?: "暂无崩溃记录",
                            fontSize = 11.sp,
                            color = D.textDim,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "分享/复制崩溃日志",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = D.accent,
                            modifier = Modifier.clickable {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, crash ?: "无崩溃记录")
                                }
                                startActivity(Intent.createChooser(send, "崩溃日志"))
                            }
                        )
                    }
                }

                // 2. Tab Page Content
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when (tab) {
                        0 -> SearchTabContent(
                            query = query,
                            onQueryChange = { query = it },
                            onSearch = { doSearch() },
                            searching = searching,
                            status = status,
                            results = results,
                            playerState = playerState,
                            quality = quality,
                            favVersion = favVersion,
                            onPlayQueue = { list, idx ->
                                Player.quality = quality
                                Player.playQueue(list, idx)
                                ensureService()
                            }
                        )
                        1 -> FavoritesTabContent(
                            favVersion = favVersion,
                            playerState = playerState,
                            quality = quality,
                            onPlayQueue = { list, idx ->
                                Player.quality = quality
                                Player.playQueue(list, idx)
                                ensureService()
                            }
                        )
                        2 -> ImportTabContent(
                            importText = importText,
                            onImportTextChange = { importText = it },
                            importing = importing,
                            onImportSubmit = {
                                if (importText.isNotBlank() && !importing) {
                                    importing = true
                                    scope.launch {
                                        val songs = withContext(Dispatchers.IO) {
                                            try { Sources.importNeteasePlaylist(importText.trim()) }
                                            catch (_: Exception) { emptyList() }
                                        }
                                        importing = false
                                        if (songs.isNotEmpty()) {
                                            results = songs
                                            tab = 0
                                            status = "歌单已导入：${songs.size} 首歌曲 · 点击即播"
                                        } else {
                                            status = "导入失败：链接无效或网易云接口无响应"
                                        }
                                    }
                                }
                            }
                        )
                        3 -> SourceScriptsTabContent(
                            importText = importText,
                            onImportTextChange = { importText = it },
                            importing = importing,
                            scriptsVersion = scriptsVersion,
                            onScriptsChanged = { scriptsVersion++ },
                            onImportSubmit = {
                                if (importText.isNotBlank() && !importing) {
                                    importing = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            try {
                                                val t = importText.trim()
                                                if (t.startsWith("http")) SourceScripts.importFromUrl(t)
                                                else SourceScripts.importFromText(t)
                                            } catch (e: Exception) {
                                                null
                                            }
                                        }
                                        importing = false
                                        if (result != null && result.providerNames.isNotEmpty()) {
                                            importText = ""
                                            status = result.message
                                            scriptsVersion++
                                        } else {
                                            status = "导入失败：非有效的 LX 协议脚本"
                                        }
                                    }
                                }
                            }
                        )
                    }
                }

                // 3. Floating Mini Player Bar
                val currentSong = playerState.queue.getOrNull(playerState.index)
                if (currentSong != null) {
                    ModernMiniPlayerBar(
                        st = playerState,
                        song = currentSong,
                        onExpand = { showFullPlayer = true }
                    )
                }

                // 4. Floating Modern Bottom Navigation Bar
                val favCount = remember(favVersion) { Favorites.all.size }
                ModernBottomNav(
                    currentTab = tab,
                    onSelectTab = { tab = it },
                    favCount = favCount
                )
            }
        }

        // Full Screen Player Overlay
        if (showFullPlayer && playerState.index >= 0) {
            FullPlayerScreen(playerState) { showFullPlayer = false }
        }
    }

    // ==================== Sub-screens / Tabs ====================

    @Composable
    private fun SearchTabContent(
        query: String,
        onQueryChange: (String) -> Unit,
        onSearch: () -> Unit,
        searching: Boolean,
        status: String,
        results: List<Sources.Song>,
        playerState: Player.State,
        quality: String,
        favVersion: Int,
        onPlayQueue: (List<Sources.Song>, Int) -> Unit,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Modern Search Input Field
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(D.surface)
                    .border(1.dp, D.surfaceBorder, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        tint = D.textDim,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        textStyle = TextStyle(color = D.textPrimary, fontSize = 15.sp),
                        modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                        singleLine = true,
                        cursorBrush = SolidColor(D.accent),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Search
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text("搜索歌曲、歌手或专辑", color = D.textFaint, fontSize = 14.sp)
                            }
                            inner()
                        }
                    )
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Close, "清除", tint = D.textDim, modifier = Modifier.size(16.dp))
                        }
                    }
                    Text(
                        if (searching) "搜索中" else "搜索",
                        color = D.accent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSearch() }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }

            // Status message
            Text(
                when {
                    playerState.resolving -> "正在多源解析音频直链…"
                    playerState.error != null -> playerState.error!!
                    else -> status
                },
                fontSize = 12.sp,
                color = D.textDim,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp)
            )

            // Songs List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
            ) {
                items(results.size) { i ->
                    val song = results[i]
                    val isFav = remember(favVersion, song.id, song.source) { Favorites.isFav(song) }
                    ModernSongCard(
                        song = song,
                        isCurrent = (i == playerState.index && playerState.queue.getOrNull(i)?.id == song.id),
                        isPlaying = playerState.playing,
                        isFav = isFav,
                        onClick = { onPlayQueue(results, i) }
                    )
                }
            }
        }
    }

    @Composable
    private fun FavoritesTabContent(
        favVersion: Int,
        playerState: Player.State,
        quality: String,
        onPlayQueue: (List<Sources.Song>, Int) -> Unit,
    ) {
        val favList = remember(favVersion) { Favorites.all }
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "我的收藏 (${favList.size})",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = D.textPrimary
                )
                Spacer(Modifier.weight(1f))
                if (favList.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(D.accent.copy(alpha = 0.16f))
                            .border(1.dp, D.accent.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                            .clickable { onPlayQueue(favList, 0) }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = D.accent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("全部播放", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = D.accent)
                        }
                    }
                }
            }

            if (favList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.FavoriteBorder, null, tint = D.textFaint, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("还没有收藏任何歌曲", color = D.textDim, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("在搜索结果中点击 ♡ 即可收藏", color = D.textFaint, fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    items(favList.size) { i ->
                        val song = favList[i]
                        val isFav = remember(favVersion, song.id, song.source) { Favorites.isFav(song) }
                        ModernSongCard(
                            song = song,
                            isCurrent = (i == playerState.index && playerState.queue.getOrNull(i)?.id == song.id),
                            isPlaying = playerState.playing,
                            isFav = isFav,
                            onClick = { onPlayQueue(favList, i) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ImportTabContent(
        importText: String,
        onImportTextChange: (String) -> Unit,
        importing: Boolean,
        onImportSubmit: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Text("导入网易云歌单", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = D.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "粘贴网易云音乐分享链接或纯歌单 ID（例如 https://music.163.com/playlist?id=xxx），自动解析全部歌曲并加入播放列表。",
                fontSize = 13.sp, color = D.textDim, lineHeight = 18.sp
            )
            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(D.surface)
                    .border(1.dp, D.surfaceBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = importText,
                        onValueChange = onImportTextChange,
                        textStyle = TextStyle(color = D.textPrimary, fontSize = 14.sp),
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                        cursorBrush = SolidColor(D.accent),
                        decorationBox = { inner ->
                            if (importText.isEmpty()) Text("粘贴歌单分享链接或数字 ID", color = D.textFaint, fontSize = 13.sp)
                            inner()
                        }
                    )
                    Button(
                        onClick = onImportSubmit,
                        enabled = importText.isNotBlank() && !importing,
                        colors = ButtonDefaults.buttonColors(containerColor = D.accent, contentColor = D.bg),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(if (importing) "解析中…" else "开始导入", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    @Composable
    private fun SourceScriptsTabContent(
        importText: String,
        onImportTextChange: (String) -> Unit,
        importing: Boolean,
        scriptsVersion: Int,
        onScriptsChanged: () -> Unit,
        onImportSubmit: () -> Unit,
    ) {
        val scriptList = remember(scriptsVersion) { SourceScripts.list() }
        val scope = rememberCoroutineScope()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Text("落雪 (LX) 音源扩展", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = D.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "海音内置 QuickJS 引擎。粘贴 .js 脚本链接或代码导入，点播时自动匹配对应平台线路，无需手动绑定。",
                fontSize = 13.sp, color = D.textDim, lineHeight = 18.sp
            )
            Spacer(Modifier.height(14.dp))

            // Input card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(D.surface)
                    .border(1.dp, D.surfaceBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = importText,
                        onValueChange = onImportTextChange,
                        textStyle = TextStyle(color = D.textPrimary, fontSize = 13.sp),
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                        cursorBrush = SolidColor(D.accent),
                        decorationBox = { inner ->
                            if (importText.isEmpty()) Text("粘贴 .js 脚本下载链接或代码", color = D.textFaint, fontSize = 13.sp)
                            inner()
                        }
                    )
                    Button(
                        onClick = onImportSubmit,
                        enabled = importText.isNotBlank() && !importing,
                        colors = ButtonDefaults.buttonColors(containerColor = D.accent, contentColor = D.bg),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(if (importing) "加载中…" else "安装", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("已安装的音源线路 (${scriptList.size})", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = D.textPrimary)
            Spacer(Modifier.height(8.dp))

            if (scriptList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 30.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("还没有导入音源脚本，导入后即可解析播放", color = D.textFaint, fontSize = 12.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(scriptList.size) { i ->
                        val entry = scriptList[i]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(D.surface)
                                .border(1.dp, D.surfaceBorder, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.name, color = D.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    buildString {
                                        append(entry.providerName)
                                        if (entry.version.isNotBlank()) append(" · ${entry.version}")
                                        if (entry.author.isNotBlank()) append(" · ${entry.author}")
                                    },
                                    fontSize = 11.sp,
                                    color = if (entry.enabled) D.accent else D.textFaint
                                )
                            }
                            Switch(
                                checked = entry.enabled,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        withContext(Dispatchers.IO) { SourceScripts.setEnabled(entry.id, checked) }
                                        onScriptsChanged()
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = D.accent,
                                    checkedTrackColor = D.accent.copy(alpha = 0.4f),
                                    uncheckedThumbColor = D.textFaint,
                                    uncheckedTrackColor = D.surfaceVar,
                                )
                            )
                            Text(
                                "卸载",
                                color = D.fav,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { SourceScripts.remove(entry.id) }
                                            onScriptsChanged()
                                        }
                                    }
                                    .padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // ==================== Component: Modern Song Card ====================

    @Composable
    fun ModernSongCard(
        song: Sources.Song,
        isCurrent: Boolean,
        isPlaying: Boolean,
        isFav: Boolean,
        onClick: () -> Unit,
    ) {
        val bg = if (isCurrent) D.accent.copy(alpha = 0.12f) else Color.Transparent
        val border = if (isCurrent) D.accent.copy(alpha = 0.35f) else Color.Transparent

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .border(1.dp, border, RoundedCornerShape(14.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = rememberRipple(color = D.accent),
                    onClick = onClick
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Album Art Thumbnail
            CoverArt(song.img, song.source, isCurrent)

            Spacer(Modifier.width(12.dp))

            // Song Info & Equalizer
            Column(Modifier.weight(1f)) {
                Text(
                    song.name,
                    color = if (isCurrent) D.accent else D.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCurrent) {
                        MiniEqualizer(isPlaying = isPlaying, modifier = Modifier.padding(end = 6.dp))
                    }
                    Text(
                        listOf(song.singer, song.album, song.interval).filter { it.isNotBlank() }.joinToString(" · "),
                        color = D.textDim,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Favorite Button
            IconButton(
                onClick = { Favorites.toggle(song) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "收藏",
                    tint = if (isFav) D.fav else D.textFaint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    @Composable
    fun CoverArt(url: String, source: String, isCurrent: Boolean) {
        var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(url) {
            if (url.isNotBlank()) {
                val cached = withContext(Dispatchers.IO) { CoverLoader.get(url) }
                if (cached != null) {
                    bitmap = cached
                } else {
                    CoverLoader.getAsync(url) { bitmap = it }
                }
            }
        }
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(D.surfaceVar)
                .border(
                    1.dp,
                    if (isCurrent) D.accent.copy(alpha = 0.5f) else D.surfaceBorder,
                    RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    source.uppercase(),
                    fontSize = 11.sp,
                    color = if (isCurrent) D.accent else D.textDim,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    // ==================== Component: Floating Mini Player Bar ====================

    @Composable
    fun ModernMiniPlayerBar(
        st: Player.State,
        song: Sources.Song,
        onExpand: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        var castMenu by remember { mutableStateOf(false) }
        var castDevices by remember { mutableStateOf(listOf<Dlna.Device>()) }
        var castScanning by remember { mutableStateOf(false) }

        val frac = if (st.durationMs > 0) (st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f) else 0f

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(D.surface)
                .border(1.dp, D.surfaceBorder, RoundedCornerShape(18.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = rememberRipple(color = D.accent),
                    onClick = onExpand
                )
        ) {
            // Built-in Top Micro Progress Indicator (1.5dp)
            if (st.prepared) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(D.surfaceBorder)
                        .align(Alignment.TopStart)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(frac)
                            .fillMaxHeight()
                            .background(Brush.horizontalGradient(listOf(D.accent, Color.White)))
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mini Cover
                CoverArt(song.img, song.source, isCurrent = true)

                Spacer(Modifier.width(12.dp))

                // Title + Subtitle
                Column(Modifier.weight(1f)) {
                    Text(
                        song.name,
                        color = D.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MiniEqualizer(isPlaying = st.playing, modifier = Modifier.padding(end = 6.dp))
                        Text(
                            song.singer,
                            color = D.textDim,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // DLNA Cast Button
                Box {
                    val castingNow = Player.castDevice != null
                    IconButton(
                        onClick = {
                            if (castingNow) {
                                Player.exitCastMode()
                            } else {
                                castMenu = true
                                castScanning = true
                                scope.launch {
                                    val found = withContext(Dispatchers.IO) {
                                        try { Dlna.discover(5000) } catch (_: Exception) { emptyList() }
                                    }
                                    castDevices = found
                                    castScanning = false
                                }
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            if (castingNow) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                            contentDescription = "投屏",
                            tint = if (castingNow) D.accent else D.textDim,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = castMenu,
                        onDismissRequest = { castMenu = false },
                        modifier = Modifier
                            .background(D.surface)
                            .border(1.dp, D.surfaceBorder, RoundedCornerShape(12.dp))
                    ) {
                        if (castScanning) {
                            DropdownMenuItem(text = { Text("搜索 DLNA 音箱/电视中…", color = D.textDim) }, onClick = {})
                        } else if (castDevices.isEmpty()) {
                            DropdownMenuItem(text = { Text("未发现设备（请连同局域网）", color = D.textDim) }, onClick = { castMenu = false })
                        } else {
                            castDevices.forEach { dev ->
                                DropdownMenuItem(
                                    text = { Text(dev.name, maxLines = 1, color = D.textPrimary) },
                                    onClick = {
                                        castMenu = false
                                        Player.enterCastMode(dev)
                                    }
                                )
                            }
                        }
                    }
                }

                // Play / Pause Button
                IconButton(
                    onClick = { Player.toggle() },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(D.surfaceVar)
                ) {
                    Icon(
                        if (st.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "播放",
                        tint = D.accent,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Next Track Button
                IconButton(
                    onClick = { Player.next() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Rounded.SkipNext,
                        contentDescription = "下一曲",
                        tint = D.textDim,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }

    // ==================== Component: Modern Bottom Navigation Bar ====================

    @Composable
    fun ModernBottomNav(
        currentTab: Int,
        onSelectTab: (Int) -> Unit,
        favCount: Int,
    ) {
        val navItems = listOf(
            Triple(0, "发现", Icons.Rounded.Search),
            Triple(1, "收藏", Icons.Rounded.Favorite),
            Triple(2, "导歌单", Icons.Rounded.CloudDownload),
            Triple(3, "音源", Icons.Rounded.Extension),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(D.surface.copy(alpha = 0.95f))
                .border(1.dp, D.surfaceBorder, RoundedCornerShape(22.dp))
                .padding(vertical = 4.dp, horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            navItems.forEach { (index, label, icon) ->
                val selected = currentTab == index
                val itemBg = if (selected) D.accent.copy(alpha = 0.16f) else Color.Transparent

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(itemBg)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = rememberRipple(color = D.accent),
                            onClick = { onSelectTab(index) }
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BadgedBox(
                            badge = {
                                if (index == 1 && favCount > 0) {
                                    Badge(containerColor = D.accent, contentColor = D.bg) {
                                        Text("$favCount", fontSize = 9.sp)
                                    }
                                }
                            }
                        ) {
                            Icon(
                                icon,
                                contentDescription = label,
                                tint = if (selected) D.accent else D.textDim,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        if (selected) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = D.accent
                            )
                        }
                    }
                }
            }
        }
    }

    private fun ensureService() {
        val intent = Intent(this, PlaybackService::class.java).apply { action = PlaybackService.ACTION_START }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }
}
