package com.grid.app.ui.analysis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.data.db.TransactionDao
import com.grid.app.data.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AnalysisViewModel @Inject constructor(
    private val transactionDao: TransactionDao
) : ViewModel() {
    private val _transactions = MutableStateFlow<List<Transaction>>(emptyList())

    val analysisData: StateFlow<Map<String, Double>> = MutableStateFlow(emptyMap<String, Double>())
    private val _analysisData = analysisData as MutableStateFlow

    init {
        viewModelScope.launch {
            transactionDao.getAllTransactions().collectLatest { txs ->
                _transactions.value = txs
                _analysisData.value = txs.filter { !it.isIncome }
                    .groupBy { it.category }
                    .mapValues { entry -> entry.value.sumOf { it.amount } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(
    onNavigateBack: () -> Unit,
    viewModel: AnalysisViewModel = hiltViewModel()
) {
    val analysisData by viewModel.analysisData.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spending Analysis") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text("Spending by Category", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))

            if (analysisData.isEmpty()) {
                Text("No spending data available.")
            } else {
                LazyColumn {
                    items(analysisData.toList()) { (category, amount) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(category, style = MaterialTheme.typography.bodyLarge)
                            Text("\$$amount", style = MaterialTheme.typography.bodyLarge)
                        }
                        Divider()
                    }
                }
            }
        }
    }
}
