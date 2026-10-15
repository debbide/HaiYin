package com.haiyin.player

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modern Immersive Full-screen Player (Apple Music / Spotify Ambient Glow Style).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullPlayerScreen(
    st: Player.State,
    onDismiss: () -> Unit,
) {
    val song = st.queue.getOrNull(st.index) ?: return
    val scope = rememberCoroutineScope()

    // Cover bitmap & dominant color extraction
    var bitmap by remember(song.id) { mutableStateOf<Bitmap?>(null) }
    var dominantColor by remember(song.id) { mutableStateOf(D.accent) }

    LaunchedEffect(song.img) {
        if (song.img.isNotBlank()) {
            val cached = withContext(Dispatchers.IO) { CoverLoader.get(song.img) }
            if (cached != null) {
                bitmap = cached
                dominantColor = extractDominantColor(cached)
            } else {
                CoverLoader.getAsync(song.img) { bmp ->
                    bitmap = bmp
                    if (bmp != null) dominantColor = extractDominantColor(bmp)
                }
            }
        } else {
            bitmap = null
            dominantColor = D.accent
        }
    }

    // Swipe between poster (page 0) and lyrics (page 1)
    val pagerState = rememberPagerState(pageCount = { 2 })

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Dynamic Ambient Glow Background
        AmbientGlowBackground(dominantColor = dominantColor)

        // 2. Main Content Layout
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp)
        ) {
            // Top Bar
            TopBar(
                song = song,
                onDismiss = onDismiss,
            )

            Spacer(Modifier.height(8.dp))

            // Body Area: horizontal pager (poster | lyrics)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) { page ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (page == 0) {
                            // Big Floating Album Art
                            BigCoverCard(
                                bitmap = bitmap,
                                dominantColor = dominantColor,
                                onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                            )
                        } else {
                            // Immersive full-height lyrics view with fading edges
                            PureScrollingLyrics(
                                song = song,
                                positionMs = st.positionMs,
                                durationMs = st.durationMs,
                                onDismissLyrics = { scope.launch { pagerState.animateScrollToPage(0) } }
                            )
                        }
                    }
                }
                // page indicator dots
                Row(
                    modifier = Modifier.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(2) { i ->
                        val selected = pagerState.currentPage == i
                        Box(
                            modifier = Modifier
                                .size(if (selected) 7.dp else 5.dp)
                                .clip(CircleShape)
                                .background(if (selected) D.accent else D.textFaint)
                                .clickable { scope.launch { pagerState.animateScrollToPage(i) } }
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Error banner (e.g. DLNA resume failures): fixed height so the
            // layout doesn't jump when it appears
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp),
                contentAlignment = Alignment.Center
            ) {
                val err = st.error
                if (!err.isNullOrBlank()) {
                    Text(
                        err,
                        fontSize = 12.sp,
                        color = D.fav,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }

            // Progress Slider Area
            ProgressSection(st = st)

            Spacer(Modifier.height(8.dp))

            // Main Playback Controls
            ControlsSection(st = st, song = song)

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TopBar(
    song: Sources.Song,
    onDismiss: () -> Unit,
) {
    var timerMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Dismiss button
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .size(40.dp)
                .background(D.surfaceBorder, CircleShape)
        ) {
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = "收起",
                tint = D.textPrimary,
                modifier = Modifier.size(24.dp)
            )
        }

        // Title and artist
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                song.name,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = D.textPrimary,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                listOf(song.singer, song.album).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 12.sp,
                color = D.textDim,
                maxLines = 1
            )
        }

        // Sleep Timer Button
        Box {
            IconButton(
                onClick = { timerMenu = !timerMenu },
                modifier = Modifier
                    .size(40.dp)
                    .background(D.surfaceBorder, CircleShape)
            ) {
                Icon(
                    Icons.Rounded.Timer,
                    contentDescription = "定时关闭",
                    tint = D.textDim,
                    modifier = Modifier.size(20.dp)
                )
            }

            DropdownMenu(
                expanded = timerMenu,
                onDismissRequest = { timerMenu = false },
                modifier = Modifier
                    .background(D.surface)
                    .border(1.dp, D.surfaceBorder, RoundedCornerShape(12.dp))
            ) {
                listOf(15, 30, 60, 0).forEach { m ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (m == 0) "关闭定时" else "$m 分钟后停止",
                                color = if (m == 0) D.fav else D.textPrimary,
                                fontSize = 14.sp
                            )
                        },
                        onClick = {
                            Player.setSleepTimer(m)
                            timerMenu = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BigCoverCard(
    bitmap: Bitmap?,
    dominantColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(26.dp))
            .border(1.dp, D.surfaceBorder, RoundedCornerShape(26.dp))
            .background(D.surfaceVar)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(color = dominantColor),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = D.accent,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("海音", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = D.accent)
            }
        }
    }
}

@Composable
private fun ProgressSection(st: Player.State) {
    if (!st.prepared) {
        Spacer(Modifier.height(24.dp))
        return
    }

    val frac = if (st.durationMs > 0) (st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f) else 0f

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = frac,
            onValueChange = { if (st.durationMs > 0) Player.seekTo(it) },
            colors = SliderDefaults.colors(
                thumbColor = D.textPrimary,
                activeTrackColor = D.accent,
                inactiveTrackColor = D.surfaceVar
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        ) {
            Text(fmtTime(st.positionMs), fontSize = 11.sp, color = D.textDim)
            Spacer(Modifier.weight(1f))
            Text(
                if (st.durationMs > 0) fmtTime(st.durationMs) else "流媒体",
                fontSize = 11.sp,
                color = D.textDim
            )
        }
    }
}

@Composable
private fun ControlsSection(st: Player.State, song: Sources.Song) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. Play Mode Toggle
        IconButton(onClick = {
            Player.setPlayMode(
                when (st.playMode) {
                    Player.PlayMode.SEQUENCE -> Player.PlayMode.REPEAT_ONE
                    Player.PlayMode.REPEAT_ONE -> Player.PlayMode.SHUFFLE
                    Player.PlayMode.SHUFFLE -> Player.PlayMode.SEQUENCE
                }
            )
        }) {
            val icon = when (st.playMode) {
                Player.PlayMode.SEQUENCE -> Icons.Rounded.Repeat
                Player.PlayMode.REPEAT_ONE -> Icons.Rounded.RepeatOne
                Player.PlayMode.SHUFFLE -> Icons.Rounded.Shuffle
            }
            Icon(
                icon,
                contentDescription = "播放模式",
                tint = if (st.playMode == Player.PlayMode.SEQUENCE) D.textDim else D.accent,
                modifier = Modifier.size(24.dp)
            )
        }

        // 2. Previous
        IconButton(
            onClick = { Player.previous() },
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                Icons.Rounded.SkipPrevious,
                contentDescription = "上一首",
                tint = D.textPrimary,
                modifier = Modifier.size(36.dp)
            )
        }

        // 3. Play / Pause Central Hero Button
        Box(
            modifier = Modifier
                .size(70.dp)
                .clip(CircleShape)
                .background(D.accent)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = rememberRipple(bounded = true, color = Color.White),
                    onClick = { Player.toggle() }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (st.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = "播放/暂停",
                tint = D.bg,
                modifier = Modifier.size(40.dp)
            )
        }

        // 4. Next
        IconButton(
            onClick = { Player.next() },
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                Icons.Rounded.SkipNext,
                contentDescription = "下一首",
                tint = D.textPrimary,
                modifier = Modifier.size(36.dp)
            )
        }

        // 5. Favorite Heart Button
        val isFav = Favorites.isFav(song)
        IconButton(onClick = { Favorites.toggle(song) }) {
            Icon(
                if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = "收藏",
                tint = if (isFav) D.fav else D.textDim,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}


/** Full-height immersive lyric scroll view with gradient fading mask & click-to-seek */
@Composable
private fun PureScrollingLyrics(
    song: Sources.Song,
    positionMs: Long,
    durationMs: Long,
    onDismissLyrics: () -> Unit,
) {
    var lines by remember(song.id) { mutableStateOf(listOf<Lyrics.Line>()) }
    var currentLine by remember(song.id) { mutableStateOf(-1) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(song.id) {
        lines = withContext(Dispatchers.IO) {
            try { Lyrics.fetch(song) } catch (_: Exception) { emptyList() }
        }
    }

    LaunchedEffect(positionMs, lines) {
        if (lines.isEmpty()) return@LaunchedEffect
        var idx = -1
        for ((i, l) in lines.withIndex()) {
            if (l.timeMs <= positionMs) idx = i else break
        }
        if (idx != currentLine) {
            currentLine = idx
            if (idx >= 0) {
                scope.launch {
                    val target = (idx - 2).coerceAtLeast(0)
                    listState.animateScrollToItem(target)
                }
            }
        }
    }

    if (lines.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismissLyrics),
            contentAlignment = Alignment.Center
        ) {
            Text("暂无歌词 · 轻触返回封面", fontSize = 14.sp, color = D.textFaint)
        }
        return
    }

    // Fading edges via Offscreen Compositing
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.15f to Color.Black,
                        0.85f to Color.Black,
                        1.0f to Color.Transparent
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(lines.size) { i ->
                val line = lines[i]
                val isCurrent = i == currentLine
                val lineAlpha by animateFloatAsState(
                    targetValue = if (isCurrent) 1f else 0.45f,
                    animationSpec = spring(),
                    label = "alpha"
                )

                Text(
                    text = line.text,
                    fontSize = if (isCurrent) 20.sp else 16.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    color = if (isCurrent) D.textPrimary else D.textDim.copy(alpha = lineAlpha),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            if (durationMs > 0) {
                                val targetFrac = (line.timeMs.toFloat() / durationMs).coerceIn(0f, 1f)
                                Player.seekTo(targetFrac)
                            }
                        }
                        .padding(vertical = 12.dp, horizontal = 20.dp)
                )
            }
        }
    }
}

private fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
