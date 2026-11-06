package com.jason.calendaralarm.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.jason.calendaralarm.alarm.AlarmScheduler
import com.jason.calendaralarm.alarm.CalendarChangeJob
import com.jason.calendaralarm.calendar.CalendarEvent
import com.jason.calendaralarm.calendar.CalendarRepository
import com.jason.calendaralarm.calendar.UpcomingEvents
import com.jason.calendaralarm.calendar.whenLabel
import com.jason.calendaralarm.permissions.PermissionStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface EventsState {
    data object Loading : EventsState
    data class Loaded(val result: UpcomingEvents, val nextAlarm: List<CalendarEvent>) : EventsState
    data class Failed(val message: String) : EventsState
}

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = LocalActivity.current

    fun readGranted() = PermissionStep.entries.associateWith { it.isGranted(context) }

    var granted by remember { mutableStateOf(readGranted()) }
    // Steps the user denied with "don't ask again"; these can only be fixed from app settings.
    var blocked by remember { mutableStateOf(emptySet<PermissionStep>()) }
    var pendingStep by remember { mutableStateOf<PermissionStep?>(null) }

    var refreshKey by remember { mutableIntStateOf(0) }

    // Permissions and events can both change outside the app, so re-check whenever we come back.
    LifecycleResumeEffect(Unit) {
        granted = readGranted()
        refreshKey++
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        val step = pendingStep ?: return@rememberLauncherForActivityResult
        pendingStep = null
        granted = readGranted()
        val permission = step.runtimePermission
        if (!isGranted && permission != null && activity?.shouldShowRequestPermissionRationale(permission) == false) {
            blocked = blocked + step
        }
    }

    fun request(step: PermissionStep) {
        val permission = step.runtimePermission
        if (permission != null && step !in blocked) {
            pendingStep = step
            permissionLauncher.launch(permission)
        } else {
            try {
                context.startActivity(step.settingsIntent(context))
            } catch (e: ActivityNotFoundException) {
                context.startActivity(PermissionStep.appDetailsIntent(context))
            }
        }
    }

    val currentStep = PermissionStep.entries.firstOrNull { granted[it] != true }
    val allGranted = currentStep == null

    var eventsState by remember { mutableStateOf<EventsState>(EventsState.Loading) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(allGranted, refreshKey) {
        if (!allGranted) {
            // Alarms only need calendar and exact-alarm access, so keep them current before the checklist is done.
            withContext(Dispatchers.IO) {
                AlarmScheduler.scheduleNext(context)
                CalendarChangeJob.register(context)
            }
            return@LaunchedEffect
        }
        if (eventsState !is EventsState.Loaded) eventsState = EventsState.Loading
        eventsState = try {
            withContext(Dispatchers.IO) {
                val upcoming = CalendarRepository.loadUpcoming(context)
                val nextAlarm = AlarmScheduler.scheduleNext(context)
                CalendarChangeJob.register(context)
                EventsState.Loaded(upcoming, nextAlarm)
            }
        } catch (e: Exception) {
            EventsState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Calendar Alarm", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (currentStep == null) {
                    "All set. Alarms will keep working in the background."
                } else {
                    "Step ${currentStep.ordinal + 1} of ${PermissionStep.entries.size}: set up access so alarms can work reliably."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(PermissionStep.entries, key = { it.name }) { step ->
            PermissionStepCard(
                step = step,
                isGranted = granted[step] == true,
                isCurrent = step == currentStep,
                isBlocked = step in blocked,
                onGrant = { request(step) },
            )
        }

        if (allGranted) {
            when (val state = eventsState) {
                EventsState.Loading -> item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is EventsState.Failed -> item {
                    Text(
                        "Couldn't read the calendar: ${state.message}",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                is EventsState.Loaded -> {
                    val (calendars, events) = state.result
                    item {
                        NextAlarmCard(
                            nextAlarm = state.nextAlarm,
                            onTest = {
                                scope.launch(Dispatchers.IO) { AlarmScheduler.scheduleTest(context) }
                                Toast.makeText(context, "Test alarm in 10 seconds. Lock the phone to see it full screen.", Toast.LENGTH_LONG).show()
                            },
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Next 10 events",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = { refreshKey++ }) { Text("Refresh") }
                        }
                    }
                    item {
                        Text(
                            "Reading from $calendars visible calendar${if (calendars == 1) "" else "s"}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (events.isEmpty()) {
                        item { Text("No events in the next year.") }
                    }
                    items(events, key = { it.instanceId }) { EventRow(it) }
                }
            }
        }
    }
}

@Composable
private fun PermissionStepCard(
    step: PermissionStep,
    isGranted: Boolean,
    isCurrent: Boolean,
    isBlocked: Boolean,
    onGrant: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) colors.primaryContainer else colors.surfaceContainer,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(if (isGranted) colors.primary else colors.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isGranted) "✓" else "${step.ordinal + 1}",
                    color = if (isGranted) colors.onPrimary else colors.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(step.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    step.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                when {
                    isGranted -> Text(
                        "Done",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    isCurrent -> {
                        if (isBlocked) {
                            Text(
                                "Permission was denied. Tap Open settings, then Permissions, and allow it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.error,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        } else {
                            Text(
                                step.guidance,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onPrimaryContainer,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        Button(onClick = onGrant, modifier = Modifier.padding(top = 8.dp)) {
                            Text(if (step.runtimePermission != null && !isBlocked) "Grant" else "Open settings")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NextAlarmCard(nextAlarm: List<CalendarEvent>, onTest: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Next alarm", style = MaterialTheme.typography.labelLarge, color = colors.onSecondaryContainer)
            val first = nextAlarm.firstOrNull()
            if (first == null) {
                Text(
                    "None scheduled. Add an event to your Alarm calendar, or put #alarm in an event's " +
                        "title or description, to ring when it starts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSecondaryContainer,
                )
            } else {
                Text(
                    nextAlarm.joinToString(", ") { it.title },
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSecondaryContainer,
                )
                Text(first.whenLabel(), style = MaterialTheme.typography.bodyMedium, color = colors.onSecondaryContainer)
            }
            OutlinedButton(onClick = onTest, modifier = Modifier.padding(top = 8.dp)) {
                Text("Test alarm in 10 seconds")
            }
        }
    }
}

@Composable
private fun EventRow(event: CalendarEvent) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(Color(event.calendarColor), CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    event.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(event.whenLabel(), style = MaterialTheme.typography.bodyMedium)
                if (event.ringsAtStart) {
                    Text(
                        "Rings at start",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                val details = listOfNotNull(event.location, event.calendarName.takeIf { it.isNotEmpty() })
                if (details.isNotEmpty()) {
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
