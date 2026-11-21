package com.meepleradar.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meepleradar.data.model.Offer
import com.meepleradar.ui.incoming.availabilityColor
import com.meepleradar.ui.incoming.availabilityLabel
import com.meepleradar.ui.incoming.offerPriceColor
import com.meepleradar.ui.incoming.priceLabel

@Composable
internal fun OfferRow(offer: Offer, allOffers: List<Offer>, isBestOffer: Boolean, onOpen: () -> Unit) {
    val priceColor = offerPriceColor(offer, allOffers)
    val stockColor = availabilityColor(offer)
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (priceColor == MaterialTheme.colorScheme.onSurfaceVariant) {
                MaterialTheme.colorScheme.surface
            } else {
                priceColor.copy(alpha = 0.08f)
            },
        ),
        border = BorderStroke(
            1.dp,
            if (priceColor == MaterialTheme.colorScheme.onSurfaceVariant) priceColor.copy(alpha = 0.25f) else priceColor,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = offer.sourceName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (isBestOffer) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                    ) {
                        Text(
                            text = "PREZZO MIGLIORE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                availabilityLabel(offer)?.let { status ->
                    Spacer(Modifier.height(2.dp))
                    Surface(
                        shape = CircleShape,
                        color = stockColor.copy(alpha = 0.15f),
                    ) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            color = stockColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = priceLabel(offer),
                    style = MaterialTheme.typography.titleLarge,
                    color = priceColor,
                    fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = onOpen,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ShoppingCart,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Apri", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
internal fun ManualProductForm(
    content: DetailUiState.Content,
    onToggleEditing: () -> Unit,
    onUpdateField: (
        title: String?,
        url: String?,
        price: Double?,
        currency: String?,
    ) -> Unit,
    onSave: () -> Unit,
) {
    var title by remember { mutableStateOf(content.manualProductTitle ?: "") }
    var url by remember { mutableStateOf(content.manualProductUrl ?: "") }
    var price by remember { mutableStateOf(content.manualProductPrice?.toString() ?: "") }
    var currency by remember { mutableStateOf(content.manualProductCurrency ?: "") }

    LaunchedEffect(
        content.manualProductTitle,
        content.manualProductUrl,
        content.manualProductPrice,
        content.manualProductCurrency,
    ) {
        title = content.manualProductTitle ?: ""
        url = content.manualProductUrl ?: ""
        price = content.manualProductPrice?.toString() ?: ""
        currency = content.manualProductCurrency ?: ""
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Inserimento prodotto manuale",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            OutlinedTextField(
                value = title,
                onValueChange = { onUpdateField(it, url, price.toDoubleOrNull(), currency) },
                label = { Text("Titolo prodotto") },
                placeholder = { Text("Es. Catan") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = url,
                onValueChange = { onUpdateField(title, it, price.toDoubleOrNull(), currency) },
                label = { Text("URL prodotto") },
                placeholder = { Text("Es. https://example.com/prodotto") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = price,
                    onValueChange = { p -> onUpdateField(title, url, p.toDoubleOrNull(), currency) },
                    label = { Text("Prezzo") },
                    placeholder = { Text("0.00") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )

                OutlinedTextField(
                    value = currency,
                    onValueChange = { c -> onUpdateField(title, url, price.toDoubleOrNull(), c) },
                    label = { Text("Valuta") },
                    placeholder = { Text("EUR") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            content.manualProductError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (content.isSavingToDb) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        "Salvataggio in corso…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onToggleEditing() },
                    enabled = !content.isSavingToDb,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Annulla")
                }

                Button(
                    onClick = { onSave() },
                    enabled = !content.isSavingToDb && title.isNotBlank() && url.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Salva prodotto")
                }
            }
        }
    }
}
