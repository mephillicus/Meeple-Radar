package com.meepleradar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meepleradar.data.model.IncomingItem
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun AlphabetFastScroller(
    items: List<IncomingItem>,
    lazyListState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val alphabet = remember { ('A'..'Z').toList() + '#' }
    var draggingLetter by remember { mutableStateOf<Char?>(null) }
    var barHeight by remember { mutableStateOf(1f) }

    val firstVisibleIndex = lazyListState.firstVisibleItemIndex
    val currentLetter = remember(firstVisibleIndex, items) {
        if (items.isNotEmpty() && firstVisibleIndex in items.indices) {
            items[firstVisibleIndex].title.trim().uppercase(Locale.ROOT).firstOrNull()?.takeIf { it.isLetter() } ?: '#'
        } else {
            null
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(44.dp)
                .pointerInput(alphabet) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            val index = (offset.y / (barHeight / alphabet.size)).toInt().coerceIn(0, alphabet.lastIndex)
                            val char = alphabet[index]
                            draggingLetter = char
                            scrollToLetter(char, items, lazyListState, coroutineScope)
                        },
                        onDragEnd = { draggingLetter = null },
                        onDragCancel = { draggingLetter = null },
                        onVerticalDrag = { change, _ ->
                            val y = change.position.y.coerceIn(0f, barHeight.coerceAtLeast(1f))
                            val index = (y / (barHeight / alphabet.size)).toInt().coerceIn(0, alphabet.lastIndex)
                            val char = alphabet[index]
                            if (draggingLetter != char) {
                                draggingLetter = char
                                scrollToLetter(char, items, lazyListState, coroutineScope)
                            }
                        },
                    )
                }
                .padding(end = 4.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                .padding(horizontal = 6.dp, vertical = 10.dp)
                .onGloballyPositioned { coordinates ->
                    barHeight = coordinates.size.height.toFloat()
                },
            verticalArrangement = Arrangement.spacedBy(1.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            alphabet.forEach { char ->
                val isSelected = draggingLetter == char
                val isCurrent = currentLetter == char && draggingLetter == null
                val scale = if (isSelected) 2.0f else if (isCurrent) 1.3f else 1f
                val fontSize = if (isSelected) 16.sp else if (isCurrent) 11.sp else 9.sp

                Text(
                    text = char.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = fontSize,
                    fontWeight = if (isSelected || isCurrent) FontWeight.ExtraBold else FontWeight.Medium,
                    color = if (isSelected || isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .scale(scale)
                        .clickable {
                            draggingLetter = char
                            scrollToLetter(char, items, lazyListState, coroutineScope)
                        }
                        .padding(horizontal = 4.dp, vertical = 0.5.dp),
                )
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
