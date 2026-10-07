package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.nuvio.app.isDesktop
import kotlinx.coroutines.launch

data class NuvioDropdownOption(
    val key: String,
    val label: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NuvioDropdownChip(
    title: String,
    label: String,
    selectedKey: String?,
    options: List<NuvioDropdownOption>,
    enabled: Boolean = true,
    onSelected: (NuvioDropdownOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    var isSheetVisible by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(tokens.shapes.compactCard)
                .background(tokens.colors.surface)
                .then(
                    if (enabled) {
                        Modifier.clickable { isSheetVisible = true }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = NuvioTokens.Space.s12, vertical = tokens.components.chipVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) tokens.colors.textPrimary else tokens.colors.textDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(NuvioTokens.Icon.sm + NuvioTokens.Space.s2),
                tint = if (enabled) tokens.colors.textMuted else tokens.colors.borderDefault,
            )
        }

        if (isDesktop && isSheetVisible) {
            val menuOffsetY = with(LocalDensity.current) { 44.dp.roundToPx() }
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, menuOffsetY),
                onDismissRequest = { isSheetVisible = false },
                properties = PopupProperties(focusable = true),
            ) {
                val listState = rememberLazyListState()
                val rowHeight = 44.dp
                Box(
                    modifier = Modifier
                        .widthIn(min = 220.dp, max = 360.dp)
                        .height(rowHeight * options.size.coerceAtMost(8).coerceAtLeast(1))
                        .clip(tokens.shapes.compactCard)
                        .background(tokens.colors.surfacePopover),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 14.dp),
                    ) {
                        items(options) { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(rowHeight)
                                    .clickable {
                                        onSelected(option)
                                        isSheetVisible = false
                                    }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = option.label,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = tokens.colors.textPrimary,
                                )
                                if (option.key == selectedKey) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = tokens.colors.accent,
                                        modifier = Modifier.size(tokens.icons.md),
                                    )
                                }
                            }
                        }
                    }
                    NuvioLazyListVerticalScrollIndicator(
                        state = listState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }

    if (isSheetVisible && !isDesktop) {
        NuvioDropdownOptionsSheet(
            title = title,
            options = options,
            selectedKey = selectedKey,
            sheetState = sheetState,
            onDismiss = {
                coroutineScope.launch {
                    dismissNuvioBottomSheet(
                        sheetState = sheetState,
                        onDismiss = { isSheetVisible = false },
                    )
                }
            },
            onSelected = { option ->
                onSelected(option)
                coroutineScope.launch {
                    dismissNuvioBottomSheet(
                        sheetState = sheetState,
                        onDismiss = { isSheetVisible = false },
                    )
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NuvioDropdownOptionsSheet(
    title: String,
    options: List<NuvioDropdownOption>,
    selectedKey: String?,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onSelected: (NuvioDropdownOption) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    NuvioModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(tokens.spacing.screenHorizontal)),
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = tokens.spacing.screenHorizontal, vertical = NuvioTokens.Space.s14),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
            )
            NuvioBottomSheetDivider()
            val listState = rememberLazyListState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = tokens.breakpoints.largePhone),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 10.dp),
                ) {
                    itemsIndexed(options) { index, option ->
                        NuvioBottomSheetActionRow(
                            title = option.label,
                            onClick = { onSelected(option) },
                            trailingContent = {
                                if (option.key == selectedKey) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = tokens.colors.accent,
                                        modifier = Modifier.size(tokens.icons.md),
                                    )
                                }
                            },
                        )
                        if (index < options.lastIndex) {
                            NuvioBottomSheetDivider()
                        }
                    }
                }
                NuvioLazyListVerticalScrollIndicator(
                    state = listState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight(),
                )
            }
        }
    }
}
