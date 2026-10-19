package com.meepleradar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.meepleradar.data.remote.BoardGameImageLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun GameCover(
    imageUrl: String?,
    title: String,
    bggId: Int?,
    imageLookup: BoardGameImageLookup,
    modifier: Modifier = Modifier,
) {
    val state = produceState<CoverState>(
        initialValue = imageUrl?.takeIf(String::isNotBlank)?.let(CoverState::Ready) ?: CoverState.Loading,
        imageUrl,
        title,
        bggId,
        imageLookup,
    ) {
        if (imageUrl.isNullOrBlank()) {
            value = try {
                val found = withTimeoutOrNull(2500L) {
                    imageLookup.findImage(title, bggId)
                }
                found?.let(CoverState::Ready) ?: CoverState.Placeholder
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CoverState.Placeholder
            }
        } else {
            value = CoverState.Ready(imageUrl)
        }
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when (val cover = state.value) {
            is CoverState.Ready -> AsyncImage(
                model = cover.url,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            CoverState.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            CoverState.Placeholder -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

private sealed interface CoverState {
    data class Ready(val url: String) : CoverState
    data object Loading : CoverState
    data object Placeholder : CoverState
}
