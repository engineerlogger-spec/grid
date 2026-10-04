package com.grid.app.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.grid.app.data.model.Transaction
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToAddTransaction: () -> Unit,
    onNavigateToAnalysis: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val transactions by viewModel.transactions.collectAsState()
    val monthlyGoal by viewModel.monthlyGoal.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()

    var showSalaryDialog by remember { mutableStateOf(false) }
    var salaryInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(monthlyGoal) {
        if (monthlyGoal == null) {
            showSalaryDialog = true
        } else {
            showSalaryDialog = false
        }
    }

    if (showSalaryDialog) {
        AlertDialog(
            onDismissRequest = { /* Require input */ },
            title = { Text("Welcome to a new month!") },
            text = {
                OutlinedTextField(
                    value = salaryInput,
                    onValueChange = { salaryInput = it },
                    label = { Text("Enter your expected salary/income") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    val income = salaryInput.toDoubleOrNull()
                    if (income != null) {
                        viewModel.saveIncome(income)
                        showSalaryDialog = false
                    }
                }) {
                    Text("Save")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Grid Dashboard") },
                actions = {
                    IconButton(onClick = {
                        scope.launch { viewModel.performBackup() }
                    }) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Filled.CloudSync, contentDescription = "Backup to Drive")
                        }
                    }
                    IconButton(onClick = onNavigateToAnalysis) {
                        Icon(Icons.Filled.Analytics, contentDescription = "Analysis")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToAddTransaction) {
                Icon(Icons.Filled.Add, contentDescription = "Add Transaction")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Income: \$${monthlyGoal?.expectedIncome ?: 0.0}", style = MaterialTheme.typography.titleLarge)
                    Text("Target Spending: \$${monthlyGoal?.targetSpending ?: 0.0}")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Recent Transactions", style = MaterialTheme.typography.titleMedium)

            LazyColumn {
                items(transactions) { transaction ->
                    TransactionItem(transaction = transaction)
                }
            }
        }
    }
}

@Composable
fun TransactionItem(transaction: Transaction) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(transaction.title, style = MaterialTheme.typography.bodyLarge)
            Text(transaction.category, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = (if (transaction.isIncome) "+" else "-") + "\$${transaction.amount}",
            color = if (transaction.isIncome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
