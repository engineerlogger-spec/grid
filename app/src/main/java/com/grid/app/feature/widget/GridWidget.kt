package com.grid.app.feature.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.grid.app.MainActivity
import com.grid.app.R
import com.grid.app.core.data.prefs.SettingsRepository
import com.grid.app.core.data.repo.LedgerListener
import com.grid.app.core.data.repo.PlanRepository
import com.grid.app.core.data.repo.TransactionRepository
import com.grid.app.core.di.AppScope
import com.grid.app.core.insights.DashboardCalculator
import com.grid.app.core.insights.LedgerEntry
import com.grid.app.core.money.MoneyFormatter
import com.grid.app.core.notify.LaunchTarget
import com.grid.app.core.time.AppClock
import com.grid.app.core.time.BudgetPeriods
import com.grid.app.feature.common.toLocalDate
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun settings(): SettingsRepository
    fun transactions(): TransactionRepository
    fun plans(): PlanRepository
    fun clock(): AppClock
    fun formatter(): MoneyFormatter
}

private data class WidgetData(
    val label: String,
    val amount: String,
    val sub: String,
    val progress: Float,
    val over: Boolean,
    val dayLine: String,
)

/** Home-screen widget: left to spend, progress against the goal, and a one-tap "+". */
class GridWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = load(context)
        provideContent { Content(context, data) }
    }

    private suspend fun load(context: Context): WidgetData {
        val ep = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val s = ep.settings().settings.first()
        val clock = ep.clock()
        val today = clock.today()
        val period = BudgetPeriods.periodFor(today, s.periodStartDay)
        val txs = ep.transactions().observePeriod(period).first()
        val goal = ep.plans().observeGoal(period).first()
        val summary = DashboardCalculator.summarize(
            period, today, goal,
            txs.filter { it.currency == s.currency }.map { LedgerEntry(it.type, it.amountMinor, it.category.id, it.occurredAt.toLocalDate(clock.zone)) },
        )
        val formatter = ep.formatter()
        val left = summary.leftMinor
        val over = left != null && left < 0
        fun money(v: Long) = formatter.format(v, s.currency, masked = s.hideAmounts)
        return WidgetData(
            label = context.getString(if (over) R.string.home_over_budget else if (left != null) R.string.home_left_to_spend else R.string.home_spent).uppercase(),
            amount = money(left?.let { kotlin.math.abs(it) } ?: summary.spentMinor),
            sub = when {
                left == null -> ""
                over -> context.getString(R.string.home_over_by)
                else -> context.getString(R.string.widget_per_day, money(summary.perDayMinor ?: 0))
            },
            progress = if (goal != null && goal > 0) (summary.spentMinor.toFloat() / goal).coerceIn(0f, 1f) else 0f,
            over = over,
            dayLine = context.getString(R.string.home_day_of, summary.dayNumber, summary.length),
        )
    }

    @androidx.compose.runtime.Composable
    private fun Content(context: Context, data: WidgetData) {
        val ink = Color(0xFF0B0D0E)
        val tile = Color(0xFF15191B)
        val lime = Color(0xFFC8F560)
        val muted = Color(0xFF8E979B)
        val coral = Color(0xFFFF6B5A)
        val open = actionStartActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val add = actionStartActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(LaunchTarget.EXTRA, LaunchTarget.ADD_EXPENSE.name),
        )
        Column(
            modifier = GlanceModifier.fillMaxSize().cornerRadius(22.dp).background(ColorProvider(tile)).padding(16.dp).clickable(open),
        ) {
            Text(data.label, style = TextStyle(color = ColorProvider(muted), fontSize = 11.sp, fontWeight = FontWeight.Medium))
            Text(data.amount, style = TextStyle(color = ColorProvider(if (data.over) coral else Color(0xFFF2F4F3)), fontSize = 28.sp, fontWeight = FontWeight.Bold))
            if (data.sub.isNotEmpty()) Text(data.sub, style = TextStyle(color = ColorProvider(if (data.over) muted else lime), fontSize = 12.sp))
            Spacer(GlanceModifier.defaultWeight())
            LinearProgressIndicator(
                progress = data.progress,
                modifier = GlanceModifier.fillMaxWidth().height(6.dp).cornerRadius(3.dp),
                color = ColorProvider(if (data.over) coral else lime),
                backgroundColor = ColorProvider(Color(0xFF1B2023)),
            )
            Spacer(GlanceModifier.height(10.dp))
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(data.dayLine, style = TextStyle(color = ColorProvider(muted), fontSize = 12.sp), modifier = GlanceModifier.defaultWeight())
                Box(
                    GlanceModifier.size(40.dp).cornerRadius(14.dp).background(ColorProvider(lime)).clickable(add),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+", style = TextStyle(color = ColorProvider(ink), fontSize = 24.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

class GridWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GridWidget()
}

/** Keeps the widget in step with the ledger (bound into the LedgerListener set). */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val scope: CoroutineScope,
) : LedgerListener {
    override suspend fun onLedgerChanged() {
        scope.launch { runCatching { GridWidget().updateAll(context) } }
    }
}
