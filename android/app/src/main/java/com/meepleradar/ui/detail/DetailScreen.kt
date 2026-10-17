package com.meepleradar.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.meepleradar.data.model.Offer
import com.meepleradar.ui.components.EmptyView
import com.meepleradar.ui.components.LoadingView
import com.meepleradar.ui.components.RatingBadge
import com.meepleradar.ui.incoming.dateLabel
import com.meepleradar.ui.incoming.priceLabel

@Composable
fun DetailScreen(vm: DetailViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("Indietro") }
            }
            when (val current = state) {
                DetailUiState.Loading -> LoadingView()

                DetailUiState.NotFound -> EmptyView("Gioco non trovato")

                is DetailUiState.Content -> DetailContent(current)
            }
        }
    }
}

@Composable
private fun DetailContent(content: DetailUiState.Content) {
    val uriHandler = LocalUriHandler.current

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            DetailHeader(content)
        }
        items(content.offers) { offer ->
            OfferRow(
                offer = offer,
                onOpen = { runCatching { uriHandler.openUri(offer.url) } },
            )
        }
    }
}

@Composable
private fun DetailHeader(content: DetailUiState.Content) {
    Row(modifier = Modifier.fillMaxWidth()) {
        AsyncImage(
            model = content.item.imageUrl,
            contentDescription = content.item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(96.dp).clip(MaterialTheme.shapes.small),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = content.item.title,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(dateLabel(content.item), style = MaterialTheme.typography.bodySmall)
            content.item.game?.bggRating?.let { RatingBadge(it) }
        }
    }
}

@Composable
private fun OfferRow(offer: Offer, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(offer.sourceName, style = MaterialTheme.typography.titleMedium)
                offer.availability?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = priceLabel(offer),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Button(onClick = onOpen, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Apri")
                }
            }
        }
    }
}
