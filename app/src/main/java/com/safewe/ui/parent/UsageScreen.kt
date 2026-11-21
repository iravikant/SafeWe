package com.safewe.ui.parent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safewe.data.remote.*
import com.safewe.ui.ErrorBanner
import com.safewe.ui.GradientProgress
import com.safewe.ui.GradientProgressIndeterminate
import com.safewe.ui.HeroCard
import com.safewe.ui.SectionCard
import com.safewe.ui.theme.Brand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import javax.inject.Inject

data class UsageUi(
    val loading: Boolean = true,
    val result: UsageResult? = null,
    val error: String? = null,
    val periodDays: Int = 1
)

@HiltViewModel
class UsageViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val commands: CommandRepository
) : ViewModel() {
    private val childUid: String = checkNotNull(saved["childUid"])
    private val _s = MutableStateFlow(UsageUi())
    val state: StateFlow<UsageUi> = _s

    init { refresh() }

    fun setPeriod(d: Int) = _s.update { it.copy(periodDays = d) }

    fun refresh() = viewModelScope.launch {
        _s.update { it.copy(loading = true, error = null) }
        runCatching {
            val id = commands.requestUsage(childUid, 31)
            val res = withTimeoutOrNull(60_000) { commands.observeUsage(childUid, id).filterNotNull().first() }
            if (res != null) runCatching { commands.cleanup(childUid, id) }
            res
        }.onSuccess { r ->
            val err = when {
                r == null -> "No response. The child device may be offline or sharing is stopped."
                r.error == "NO_PERMISSION" -> "Usage access is turned off on the child's device. They can turn it on in the SafeWe app."
                r.days.isEmpty() -> "No usage data has been collected yet. Totals build up over the first days."
                else -> null
            }
            _s.update { it.copy(loading = false, result = r?.takeIf { x -> x.error.isEmpty() }, error = err) }
        }.onFailure { e -> _s.update { it.copy(loading = false, error = e.message) } }
    }
}

fun formatDuration(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(onBack: () -> Unit, vm: UsageViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("App usage") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = vm::refresh, enabled = !st.loading) { Icon(Icons.Filled.Refresh, "Refresh") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(1 to "Day", 7 to "Week", 30 to "Month").forEachIndexed { i, (d, label) ->
                    SegmentedButton(st.periodDays == d, { vm.setPeriod(d) }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                }
            }

            if (st.loading) SectionCard {
                Text("Asking your child's phone…", style = MaterialTheme.typography.bodyMedium)
                GradientProgressIndeterminate()
            }
            st.error?.let { ErrorBanner(it) }

            st.result?.takeIf { !st.loading }?.let { r ->
                val all = r.days.sortedBy { it.day }
                val anchor = all.lastOrNull()?.let { LocalDate.parse(it.day) } ?: LocalDate.now()
                val dates = (st.periodDays - 1 downTo 0).map { anchor.minusDays(it.toLong()) }
                val byDay = all.associateBy { it.day }
                val totals = dates.map { byDay[it.toString()]?.totalMs ?: 0L }
                val apps = dates.mapNotNull { byDay[it.toString()] }.flatMap { it.apps }
                    .groupBy { it.pkg }
                    .map { (pkg, l) -> UsageApp(pkg, l.last().label, l.sumOf { it.ms }) }
                    .sortedByDescending { it.ms }.take(10)
                val total = totals.sum()
                val activeDays = totals.count { it > 0 }.coerceAtLeast(1)

                HeroCard {
                    Text(
                        if (st.periodDays == 1) "SCREEN TIME (LATEST DAY)" else "TOTAL SCREEN TIME",
                        style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f)
                    )
                    Text(formatDuration(total), style = MaterialTheme.typography.displaySmall)
                    if (st.periodDays > 1) Text("Average ${formatDuration(total / activeDays)} per day", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
                }

                if (st.periodDays > 1) {
                    SectionCard {
                        Text("Trend", style = MaterialTheme.typography.titleMedium)
                        BarChart(totals, Modifier.fillMaxWidth().height(140.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(dates.first().toString().drop(5), style = MaterialTheme.typography.bodyMedium)
                            Text(dates.last().toString().drop(5), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                SectionCard {
                    Text("Most used apps", style = MaterialTheme.typography.titleMedium)
                    val max = apps.firstOrNull()?.ms?.coerceAtLeast(1L) ?: 1L
                    apps.forEachIndexed { i, a ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", Modifier.width(24.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text(a.label.ifBlank { a.pkg }, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(formatDuration(a.ms), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                            }
                            GradientProgress(a.ms.toFloat() / max)
                        }
                    }
                    if (st.periodDays > 1) Text("Based on each day's top 10 apps.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

@Composable
private fun BarChart(values: List<Long>, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val max = values.max().coerceAtLeast(1L)
        val gap = 3.dp.toPx()
        val bw = ((size.width - gap * (values.size - 1)) / values.size).coerceAtLeast(1f)
        values.forEachIndexed { i, v ->
            val x = i * (bw + gap)
            drawRoundRect(track, Offset(x, 0f), Size(bw, size.height), CornerRadius(4.dp.toPx()))
            val h = size.height * v / max
            if (h > 0f) drawRoundRect(
                Brush.verticalGradient(listOf(Brand.Lavender, color, Brand.PurpleDark), startY = size.height - h, endY = size.height),
                Offset(x, size.height - h), Size(bw, h), CornerRadius(4.dp.toPx())
            )
        }
    }
}
