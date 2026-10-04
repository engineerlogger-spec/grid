package com.grid.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grid.app.data.db.TransactionDao
import com.grid.app.data.model.MonthlyGoal
import com.grid.app.data.model.Subscription
import com.grid.app.data.model.Transaction
import com.grid.app.sync.DriveSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val transactionDao: TransactionDao,
    private val driveSyncManager: DriveSyncManager
) : ViewModel() {

    private val _transactions = MutableStateFlow<List<Transaction>>(emptyList())
    val transactions: StateFlow<List<Transaction>> = _transactions.asStateFlow()

    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    private val _monthlyGoal = MutableStateFlow<MonthlyGoal?>(null)
    val monthlyGoal: StateFlow<MonthlyGoal?> = _monthlyGoal.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            transactionDao.getAllTransactions().collectLatest {
                _transactions.value = it
            }
        }
        viewModelScope.launch {
            transactionDao.getAllSubscriptions().collectLatest {
                _subscriptions.value = it
            }
        }
        viewModelScope.launch {
            val currentMonth = getCurrentMonthYear()
            transactionDao.getMonthlyGoal(currentMonth).collectLatest {
                _monthlyGoal.value = it
            }
        }
    }

    fun saveIncome(income: Double) {
        viewModelScope.launch {
            val goal = MonthlyGoal(
                monthYear = getCurrentMonthYear(),
                expectedIncome = income,
                targetSpending = income * 0.8 // default rule of thumb
            )
            transactionDao.insertMonthlyGoal(goal)
        }
    }

    fun performBackup() {
        viewModelScope.launch {
            _isSyncing.value = true
            driveSyncManager.performBackup()
            _isSyncing.value = false
        }
    }

    private fun getCurrentMonthYear(): String {
        val calendar = Calendar.getInstance()
        val month = calendar.get(Calendar.MONTH) + 1
        val year = calendar.get(Calendar.YEAR)
        return "${month}-${year}"
    }
}
