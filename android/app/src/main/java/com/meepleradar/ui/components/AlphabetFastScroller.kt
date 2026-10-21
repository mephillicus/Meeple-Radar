package com.meepleradar.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meepleradar.data.model.IncomingItem
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AlphabetFastScroller(
    items: List<IncomingItem>,
    lazyListState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val alphabet = remember { listOf('#') + ('A'..'Z').toList() }
    var draggingLetter by remember { mutableStateOf<Char?>(null) }
    var isTouchingBar by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }

    val isScrollInProgress = lazyListState.isScrollInProgress

    LaunchedEffect(isScrollInProgress, isTouchingBar, draggingLetter) {
        if (isScrollInProgress || isTouchingBar || draggingLetter != null) {
            visible = true
        } else {
            delay(2000L)
            if (!lazyListState.isScrollInProgress && !isTouchingBar && draggingLetter == null) {
                visible = false
            }
        }
    }

    val availableLetters = remember(items) {
        items.map { item ->
            item.title.trim().uppercase(Locale.ROOT).firstOrNull()?.takeIf { it.isLetter() } ?: '#'
        }.toSet()
    }

    val firstVisibleIndex = lazyListState.firstVisibleItemIndex
    val safeIndex = if (items.isNotEmpty()) firstVisibleIndex.coerceIn(0, items.lastIndex) else 0
    val currentLetter = remember(safeIndex, items) {
        if (items.isNotEmpty()) {
            items[safeIndex].title.trim().uppercase(Locale.ROOT).firstOrNull()?.takeIf { it.isLetter() } ?: '#'
        } else {
            null
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(300)),
        exit = fadeOut(animationSpec = tween(300)),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Column(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 6.dp, vertical = 10.dp)
                    .pointerInput(alphabet) {
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                isTouchingBar = true
                                val barHeight = size.height.toFloat()
                                val index = (offset.y / (barHeight / alphabet.size)).toInt().coerceIn(0, alphabet.lastIndex)
                                val char = alphabet[index]
                                if (char in availableLetters) {
                                    draggingLetter = char
                                    scrollToLetter(char, items, lazyListState, coroutineScope)
                                }
                            },
                            onDragEnd = {
                                isTouchingBar = false
                                draggingLetter = null
                            },
                            onDragCancel = {
                                isTouchingBar = false
                                draggingLetter = null
                            },
                            onVerticalDrag = { change, _ ->
                                isTouchingBar = true
                                val barHeight = size.height.toFloat()
                                val y = change.position.y.coerceIn(0f, barHeight.coerceAtLeast(1f))
                                val index = (y / (barHeight / alphabet.size)).toInt().coerceIn(0, alphabet.lastIndex)
                                val char = alphabet[index]
                                if (char in availableLetters && draggingLetter != char) {
                                    draggingLetter = char
                                    scrollToLetter(char, items, lazyListState, coroutineScope)
                                }
                            },
                        )
                    },
                verticalArrangement = Arrangement.spacedBy(1.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                alphabet.forEach { char ->
                    val hasItems = char in availableLetters
                    val isSelected = draggingLetter == char
                    val isCurrent = currentLetter == char && draggingLetter == null
                    val scale = if (isSelected) 2.0f else if (isCurrent) 1.3f else 1f
                    val fontSize = if (isSelected) 16.sp else if (isCurrent) 11.sp else 9.sp
                    val alpha = if (hasItems) 1.0f else 0.25f

                    Box(
                        modifier = Modifier
                            .width(28.dp)
                            .height(18.dp)
                            .alpha(alpha)
                            .clickable(enabled = hasItems) {
                                scrollToLetter(char, items, lazyListState, coroutineScope)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = char.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = fontSize,
                            fontWeight = if (isSelected || isCurrent) FontWeight.ExtraBold else FontWeight.Medium,
                            color = if (isSelected || isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale),
                        )
                    }
                }
            }

            if (draggingLetter != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .wrapContentSize(Alignment.Center),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = draggingLetter.toString(),
                            style = MaterialTheme.typography.headlineLarge,
                            fontSize = 44.sp,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

private fun scrollToLetter(
    char: Char,
    items: List<IncomingItem>,
    lazyListState: LazyListState,
    coroutineScope: CoroutineScope,
) {
    val index = items.indexOfFirst { item ->
        val firstChar = item.title.trim().uppercase(Locale.ROOT).firstOrNull() ?: '#'
        if (char == '#') !firstChar.isLetter() else firstChar == char
    }
    if (index != -1) {
        coroutineScope.launch {
            lazyListState.scrollToItem(index)
        }
    }
}
