package com.micahf.cameragps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micahf.cameragps.db.FilmDao
import com.micahf.cameragps.db.Stock
import kotlinx.coroutines.launch

/** The film stock list offered when loading a roll: add, delete, restore defaults. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StocksSheet(dao: FilmDao, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val stocks by dao.stocks().collectAsStateWithLifecycle(emptyList())
    var name by remember { mutableStateOf("") }
    var iso by remember { mutableStateOf("") }
    val exists = stocks.any { it.name.equals(name.trim(), ignoreCase = true) }
    val add = {
        val stock = Stock(name = name.trim(), iso = iso.toIntOrNull())
        scope.launch { dao.addStock(stock) }
        name = ""
        iso = ""
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Film stocks", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { scope.launch { dao.restoreDefaultStocks() } }) { Text("Restore defaults") }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                name,
                { name = it },
                label = { Text("New stock") },
                singleLine = true,
                isError = exists,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                iso,
                { iso = it.filter(Char::isDigit).take(5) },
                label = { Text("ISO") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank() && !exists) add() }),
                modifier = Modifier.width(88.dp),
            )
            FilledTonalButton(onClick = { add() }, enabled = name.isNotBlank() && !exists) { Text("Add") }
        }
        LazyColumn(Modifier.navigationBarsPadding()) {
            items(stocks, key = { it.id }) { stock ->
                ListItem(
                    headlineContent = { Text(stock.name) },
                    supportingContent = stock.iso?.let { { Text("ISO $it") } },
                    trailingContent = {
                        IconButton(onClick = { scope.launch { dao.deleteStock(stock) } }) {
                            Icon(Icons.Outlined.Delete, "Delete ${stock.name}")
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}
