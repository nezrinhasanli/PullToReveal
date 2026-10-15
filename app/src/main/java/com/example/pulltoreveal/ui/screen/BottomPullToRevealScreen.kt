package com.example.pulltoreveal.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private const val MIN_PULLING_TIME_MS = 200L

@Composable
fun BottomPullToRevealScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    // Dynamically calculate target reveal height (~9% of screen height, clamped between 60.dp and 80.dp)
    val targetItemHeight = (configuration.screenHeightDp.dp * 0.09f).coerceIn(60.dp, 80.dp)

    // Physical dimensions derived dynamically using density
    val snapThresholdPx = with(density) { (targetItemHeight * 1.15f).toPx() } // Distance required to trigger reveal
    val maxOverscrollPx = with(density) { (targetItemHeight * 2.0f).toPx() }  // Stretch limit before high resistance
    val hideScrollThresholdPx = with(density) { 40.dp.toPx() }                 // Slop threshold to prevent micro-jitters

    // =====================================================================================
    // STATE DEFINITIONS
    // =====================================================================================
    var isRevealed by remember { mutableStateOf(false) }
    val items = remember { (1..20).map { "Item #$it" } }
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val pullOffset = remember { Animatable(0f) }

    var hasTriggeredHaptic by remember { mutableStateOf(false) }
    var pullStartTime by remember { mutableLongStateOf(0L) }
    var scrollUpAccumulator by remember { mutableFloatStateOf(0f) }

    // Smoothly animates target item height using a bouncy spring when 'isRevealed' toggles
    val revealedItemHeight by animateDpAsState(
        targetValue = if (isRevealed) targetItemHeight else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "revealedItemHeightAnimation"
    )

    // Automatically keeps the bottom edge anchored in view as revealedItemHeight animates open
    LaunchedEffect(isRevealed, revealedItemHeight) {
        if (isRevealed) {
            val totalCount = lazyListState.layoutInfo.totalItemsCount
            if (totalCount > 0) {
                val scrollOffsetPx = with(density) { targetItemHeight.toPx().toInt() }
                lazyListState.scrollToItem(
                    index = totalCount - 1,
                    scrollOffset = scrollOffsetPx
                )
            }
        }
    }

    // =====================================================================================
    // BOUNDARY DETECTION: Checks if user has reached the bottom of the list
    // =====================================================================================
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = lazyListState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) {
                false
            } else {
                val lastItem = visibleItems.last()
                val isLastIndex = lastItem.index == layoutInfo.totalItemsCount - 1
                val isFullyVisible = (lastItem.offset + lastItem.size) <= layoutInfo.viewportEndOffset
                isLastIndex && isFullyVisible
            }
        }
    }

    // =====================================================================================
    // NESTED SCROLL INTERCEPTOR
    // =====================================================================================
    val nestedScrollConnection = remember(isRevealed, isAtBottom, snapThresholdPx, maxOverscrollPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // CASE 1: Collapse archive on upward drag scroll
                if (isRevealed && available.y > 0f) {
                    scrollUpAccumulator += available.y
                    if (scrollUpAccumulator >= hideScrollThresholdPx) {
                        isRevealed = false
                        scrollUpAccumulator = 0f
                    }
                    return Offset.Zero
                }
                // CASE 2: Retract active pull-up when user drags back DOWN
                if (pullOffset.value > 0f && available.y > 0f) {
                    val consumed = available.y
                    val newOffset = (pullOffset.value - consumed).coerceAtLeast(0f)
                    scope.launch { pullOffset.snapTo(newOffset) }
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source != NestedScrollSource.UserInput) {
                    return Offset.Zero
                }
                // CASE 3: Pulling UP at bottom boundary
                if (!isRevealed && isAtBottom && available.y < 0f) {
                    if (pullOffset.value == 0f) {
                        pullStartTime = System.currentTimeMillis()
                    }

                    val resistance = (1f - (pullOffset.value / maxOverscrollPx)).coerceIn(0f, 0.7f)
                    val delta = -available.y * 0.45f * resistance

                    val newOffset = (pullOffset.value + delta).coerceAtMost(maxOverscrollPx)

                    if (newOffset >= snapThresholdPx && !hasTriggeredHaptic) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        hasTriggeredHaptic = true
                    } else if (newOffset < snapThresholdPx && hasTriggeredHaptic) {
                        hasTriggeredHaptic = false
                    }

                    scope.launch { pullOffset.snapTo(newOffset) }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pullOffset.value > 0f) {
                    val pullDuration = System.currentTimeMillis() - pullStartTime
                    val isDeliberatePull = pullOffset.value >= snapThresholdPx && pullDuration >= MIN_PULLING_TIME_MS

                    if (isDeliberatePull) {
                        isRevealed = true
                        pullOffset.snapTo(0f)

                        val lastIndex = lazyListState.layoutInfo.totalItemsCount - 1
                        if (lastIndex >= 0) {
                            lazyListState.animateScrollToItem(lastIndex)
                        }
                    } else {
                        pullOffset.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
                    }

                    hasTriggeredHaptic = false
                    pullStartTime = 0L
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    val pullProgress = (pullOffset.value / snapThresholdPx).coerceIn(0f, 1f)

    // =====================================================================================
    // UI LAYOUT
    // =====================================================================================
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .nestedScroll(nestedScrollConnection)
    ) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize()
        ) {
            items(items, key = { it }) { item ->
                Items(title = item)
                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.2f), thickness = 0.5.dp)
            }

            // 2. Temporary Pull-to-Reveal Indicator
            if (!isRevealed && pullOffset.value > 0f) {
                item(key = "bottom_pull_indicator") {
                    val currentHeightDp = with(density) { pullOffset.value.toDp() }
                    BottomPullIndicator(
                        height = currentHeightDp,
                        progress = pullProgress,
                        isReadyToRelease = pullOffset.value >= snapThresholdPx
                    )
                }
            }

            // 3. Permanently revealed item expanding dynamically
            item(key = "bottom_revealed_item") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(revealedItemHeight)
                        .clipToBounds()
                ) {
                    if (revealedItemHeight > 0.dp) {
                        BottomRevealedItem(
                            targetHeight = targetItemHeight,
                            progress = if (targetItemHeight > 0.dp) (revealedItemHeight / targetItemHeight).coerceIn(0f, 1f) else 0f,
                            onHide = { isRevealed = false }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Renders the visual pull indicator bar (arrow, color change, and hint text) while pulling down/up.
 */
@Composable
private fun BottomPullIndicator(
    height: Dp,
    progress: Float,
    isReadyToRelease: Boolean
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isReadyToRelease) Color(0xFF4A90E2) else Color(0xFF2C3238),
        label = "bgColorTransition"
    )

    val arrowRotation = progress * 180f
    val textAlpha = FastOutSlowInEasing.transform(progress.coerceIn(0f, 1f))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        if (height > 32.dp) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.graphicsLayer {
                    alpha = textAlpha
                    scaleX = 0.8f + (textAlpha * 0.2f)
                    scaleY = 0.8f + (textAlpha * 0.2f)
                }
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(arrowRotation)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isReadyToRelease) "Release to reveal" else "Pull up to reveal",
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
        }
    }
}

/**
 * Renders the revealed "Hidden Items" cell at the bottom of the list.
 */
@Composable
private fun BottomRevealedItem(
    targetHeight: Dp,
    progress: Float,
    onHide: () -> Unit
) {
// Eased opacity curve (0.0f to 1.0f): Content smoothly fades in as the item expands
    val contentAlpha = FastOutSlowInEasing.transform(progress)

// Vertical parallax slide: Moves content downward from -targetHeightPx to 0px,
// synchronizing the reveal motion with the height expansion to eliminate visual jumps
    val slideOffsetY = with(LocalDensity.current) { -(1f - progress) * targetHeight.toPx() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(targetHeight)
            .background(Color(0xFFEFEFEF))
            .clickable { onHide() }
            .padding(horizontal = 16.dp)
            .graphicsLayer {
                alpha = contentAlpha
                translationY = slideOffsetY
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size((targetHeight * 0.6f).coerceIn(36.dp, 48.dp))
                .clip(CircleShape)
                .background(Color(0xFF5B6B79)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = "Archive Icon",
                tint = Color.White
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = "Hidden Items",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Scroll up to hide",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}

/**
 * Standard list item representation set to standard list height (72.dp).
 */
@Composable
private fun Items(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.LightGray)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
    }
}
