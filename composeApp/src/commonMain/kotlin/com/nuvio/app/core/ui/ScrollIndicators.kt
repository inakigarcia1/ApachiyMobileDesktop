package com.nuvio.app.core.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nuvio.app.isDesktop

@Composable
internal fun NuvioVerticalScrollIndicator(
    state: ScrollState,
    modifier: Modifier = Modifier,
) {
    if (isDesktop) {
        NuvioDesktopVerticalScrollbar(state = state, modifier = modifier)
        return
    }
    val maxValue = state.maxValue
    if (maxValue <= 0) return
    val scrollFraction by remember {
        derivedStateOf { state.value.toFloat() / maxValue.toFloat() }
    }
    BoxWithConstraints(modifier = modifier) {
        val trackHeightPx = constraints.maxHeight.toFloat()
        val thumbHeightPx = (trackHeightPx * 0.28f).coerceIn(48f, trackHeightPx)
        val thumbOffsetPx = scrollFraction * (trackHeightPx - thumbHeightPx)
        val density = LocalDensity.current
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(8.dp)
                .clip(RoundedCornerShape(100)),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = with(density) { thumbOffsetPx.toDp() })
                    .width(8.dp)
                    .height(with(density) { thumbHeightPx.toDp() })
                    .clip(RoundedCornerShape(100))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)),
            )
        }
    }
}

@Composable
internal fun NuvioLazyListVerticalScrollIndicator(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    if (isDesktop) {
        NuvioDesktopVerticalScrollbar(state = state, modifier = modifier)
        return
    }
    val layoutInfo by remember { derivedStateOf { state.layoutInfo } }
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo
    if (totalItems == 0 || visibleItems.isEmpty()) return
    if (!state.canScrollForward && !state.canScrollBackward) return

    val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
    val averageItemSize = visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
    val totalContentHeight = averageItemSize * totalItems
    if (totalContentHeight <= viewportHeight) return

    val scrollOffset = state.firstVisibleItemIndex * averageItemSize + state.firstVisibleItemScrollOffset
    val maxScroll = totalContentHeight - viewportHeight
    val scrollFraction = (scrollOffset / maxScroll).coerceIn(0f, 1f)
    val thumbHeightPx = (viewportHeight * viewportHeight / totalContentHeight).coerceIn(48f, viewportHeight)
    val thumbOffsetPx = scrollFraction * (viewportHeight - thumbHeightPx)
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .width(6.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(100))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = with(density) { thumbOffsetPx.toDp() })
                    .width(8.dp)
                    .height(with(density) { thumbHeightPx.toDp() })
                    .clip(RoundedCornerShape(100))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)),
            )
        }
    }
}
