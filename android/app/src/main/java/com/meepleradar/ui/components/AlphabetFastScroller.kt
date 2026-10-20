package com.meepleradar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meepleradar.data.model.IncomingItem
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AlphabetFastScroller(
    items: List<IncomingItem>,
    lazyListState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val alphabet = remember { ('A'..'Z').toList() + '#' }
    var selectedLetter by remember { mutableStateOf<Char?>(null) }

    val firstVisibleIndex = lazyListState.firstVisibleItemIndex
    val currentLetter = remember(firstVisibleIndex, items) {
        if (items.isNotEmpty() && firstVisibleIndex in items.indices) {
            items[firstVisibleIndex].title.trim().uppercase(Locale.ROOT).firstOrNull()?.takeIf { it.isLetter() } ?: '#'
        } else {
            null
        }
    }

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            alphabet.forEach { char ->
                val isCurrent = currentLetter == char || selectedLetter == char
                Text(
                    text = char.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Medium,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable {
                            selectedLetter = char
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
                        .padding(horizontal = 2.dp, vertical = 0.5.dp),
                )
            }
        }

        if (selectedLetter != null) {
            LaunchedEffect(selectedLetter) {
                delay(800)
                selectedLetter = null
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = selectedLetter.toString(),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
