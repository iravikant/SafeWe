package com.safewe.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.safewe.ui.theme.Brand
import com.safewe.transfer.BudgetPolicy
import com.safewe.transfer.Quota
import com.safewe.transfer.QuotaMath
import com.safewe.transfer.QuotaTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class QuotaViewModel @Inject constructor(private val tracker: QuotaTracker) : ViewModel() {
    val usage: StateFlow<Quota> = tracker.usage
    fun refresh() = tracker.refresh()
}

/** Shows this phone's estimated Firestore usage against the free (Spark) daily allowance. */
@Composable
fun QuotaCard(vm: QuotaViewModel = hiltViewModel()) {
    val u by vm.usage.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }
    val level = BudgetPolicy.level(u)
    SectionCard {
        Text("Cloud usage today (this phone)", style = MaterialTheme.typography.titleMedium)
        Meter("Reads", u.reads, QuotaMath.FREE.reads)
        Meter("Writes", u.writes, QuotaMath.FREE.writes)
        Meter("Deletes", u.deletes, QuotaMath.FREE.deletes)
        Text(
            when (level) {
                BudgetPolicy.Level.OK -> "Well within the free allowance."
                BudgetPolicy.Level.HIGH -> "Getting busy. SafeWe sends status updates less often to save quota."
                BudgetPolicy.Level.CRITICAL -> "Almost at the limit. Status updates are paused. Alerts and your requests still go through."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (level == BudgetPolicy.Level.OK) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
        )
        Text(
            "This is an estimate for this phone only. The free limits are shared by every phone using your Firebase project; " +
                "the Firebase console shows the exact totals.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun Meter(label: String, used: Long, limit: Long) {
    val fraction = if (limit > 0) (used.toFloat() / limit).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("%,d / %,d".format(used, limit), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
        GradientProgress(fraction, brush = if (fraction >= 0.9f) Brand.progressCritical else Brand.progress)
    }
}
