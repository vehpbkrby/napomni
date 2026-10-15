package ru.napomni.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Экран будильника поверх блокировки: «Готово» или «Отложить». */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    private fun show(intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val at = intent.getLongExtra(EXTRA_AT, 0)
        val r = Store.get(this, id)
        if (r == null) {
            finish()
            return
        }
        // Служба не смогла стартовать из фона — начинаем звон отсюда, пока срабатывание свежее.
        val now = System.currentTimeMillis()
        if (AlarmService.ringing != id && !r.done && now - at < AlarmService.RING_MS) AlarmService.start(this, id, at, ring = true)
        val act = { action: String ->
            Actions.handle(this, id, action)
            finish()
        }
        setContent { AppTheme { AlarmScreen(r, now, act) } }
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_AT = "at"

        fun pending(ctx: Context, id: Int, at: Long): PendingIntent = PendingIntent.getActivity(
            ctx, 1000 + id,
            Intent(ctx, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_AT, at),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

@androidx.compose.runtime.Composable
private fun AlarmScreen(r: Reminder, now: Long, act: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val t = Fmt.local(r.at)
    Surface(color = cs.surface, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            Column(
                Modifier.fillMaxWidth().align(Alignment.Center).padding(bottom = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(Fmt.hm(t), fontSize = 56.sp, fontWeight = FontWeight.Medium)
                Text(Fmt.long(r.at, now), fontSize = 16.sp, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(32.dp))
                Text(r.title, fontSize = 24.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                if (r.repeat) Text(
                    "повтор через $NAG_MIN мин, пока не нажмёте кнопку",
                    fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }
            Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                Button(
                    onClick = { act(Actions.DONE) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                ) { Text("Готово", fontSize = 18.sp) }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { act(Actions.SNOOZE_10) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) { Text("Отложить 10 мин", fontSize = 15.sp) }
                    OutlinedButton(
                        onClick = { act(Actions.SNOOZE_60) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) { Text("Отложить 1 ч", fontSize = 15.sp) }
                }
            }
        }
    }
}
