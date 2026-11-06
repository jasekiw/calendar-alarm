package com.jason.calendaralarm.alarm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jason.calendaralarm.CalendarAlarmTheme

/** Full-screen alarm shown over the lock screen while an alarm notification is ringing. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent.getIntExtra(EXTRA_ID, 0)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()

        setContent {
            CalendarAlarmTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(title, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(48.dp))
                        OutlinedButton(
                            onClick = {
                                AlarmNotifications.snooze(this@AlarmActivity, id, title, text)
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                        ) {
                            Text(
                                "Snooze ${AlarmScheduler.SNOOZE.inWholeMinutes} min",
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                AlarmNotifications.dismiss(this@AlarmActivity, id)
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                        ) {
                            Text("Dismiss", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"

        fun intent(context: Context, id: Int, title: String, text: String): Intent =
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
    }
}
