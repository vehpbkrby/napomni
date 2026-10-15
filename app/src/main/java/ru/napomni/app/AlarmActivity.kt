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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/** Экран будильника поверх блокировки: «Готово» или «Отложить». Всегда тёмный — ночью белый экран слепит. */
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
        setContent { AppTheme(forceDark = true) { AlarmScreen(r, at, act) } }
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

@Composable
private fun AlarmScreen(r: Reminder, ringAt: Long, act: (String) -> Unit) {
    val p = LocalPal.current
    val brass = isBrass
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            now = System.currentTimeMillis()
        }
    }
    val t = Fmt.local(r.at)
    val d = t.toLocalDate()
    val bg = if (brass) Brush.radialGradient(listOf(Color(0xFF18233B), Color(0xFF0B111E))) else Brush.linearGradient(listOf(Color(0xFF0A0C0D), Color(0xFF0A0C0D)))
    Box(Modifier.fillMaxSize().background(bg).safeDrawingPadding().padding(24.dp)) {
        Column(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
                if (brass) BrassDial(p.accent) else {
                    // Дуга убывает до конца звонка (а с «повторять» — до автоповтора).
                    val left = (1f - (now - ringAt).toFloat() / AlarmService.RING_MS).coerceIn(0f, 1f)
                    EmeraldRing(p.accent, left)
                }
                Text(
                    if (r.allDay) "Сегодня" else Fmt.hm(t), fontFamily = displayFont(),
                    fontSize = if (r.allDay) 44.sp else if (brass) 76.sp else 84.sp,
                    fontWeight = if (brass) FontWeight.Normal else FontWeight.Light, color = p.text,
                )
            }
            Text(
                Fmt.event(r, now), fontSize = 16.sp, color = if (isWeekend(d)) p.weekend else p.textSub,
                modifier = Modifier.padding(top = 18.dp),
            )
            Text(
                r.title, fontFamily = displayFont(), fontSize = if (brass) 26.sp else 24.sp, lineHeight = 32.sp,
                fontWeight = if (brass) FontWeight.Normal else FontWeight.Medium, color = p.text, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp),
            )
            if (r.repeat) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                val pulse = rememberInfiniteTransition(label = "pulse")
                val a by pulse.animateFloat(.25f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse), label = "a")
                Box(Modifier.size(7.dp).clip(CircleShape).background(p.accent.copy(alpha = a)))
                Text("  повтор через $NAG_MIN мин, пока не нажмёте кнопку", fontSize = 13.sp, color = p.textSub)
            }
        }
        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
            Box(
                Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (brass) p.accent else p.primary).clickable { act(Actions.DONE) },
                contentAlignment = Alignment.Center,
            ) { Text("Готово", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = if (brass) p.onAccent else p.onPrimary) }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("Отложить 10 мин" to Actions.SNOOZE_10, "Отложить 1 ч" to Actions.SNOOZE_60).forEach { (label, a) ->
                    val shape = RoundedCornerShape(14.dp)
                    Box(
                        Modifier.weight(1f).height(54.dp).clip(shape)
                            .background(if (brass) Color.Transparent else p.surfaceVar)
                            .then(if (brass) Modifier.border(1.dp, p.outline, shape) else Modifier)
                            .clickable { act(a) },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = p.text) }
                }
            }
        }
    }
}

/** Циферблат из 60 латунных рисок: часовые длиннее и ярче, на 12 часах ромб. */
@Composable
private fun BrassDial(brass: Color) {
    Canvas(Modifier.size(280.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val rOut = size.minDimension / 2
        for (i in 0 until 60) {
            if (i == 0) continue
            val hourTick = i % 5 == 0
            val len = (if (hourTick) 16.dp else 8.dp).toPx()
            val a = Math.toRadians(i * 6.0 - 90).toFloat()
            val p1 = Offset(c.x + cos(a) * rOut, c.y + sin(a) * rOut)
            val p2 = Offset(c.x + cos(a) * (rOut - len), c.y + sin(a) * (rOut - len))
            drawLine(brass.copy(alpha = if (hourTick) .95f else .45f), p1, p2, (if (hourTick) 2.dp else 1.dp).toPx(), StrokeCap.Round)
        }
        val s = 8.dp.toPx()
        rotate(45f, Offset(c.x, c.y - rOut + s)) {
            drawRect(brass, Offset(c.x - s / 2, c.y - rOut + s / 2), Size(s, s))
        }
    }
}

/** Кольцо графита и изумрудная дуга, убывающая до конца звонка, с точкой на конце. */
@Composable
private fun EmeraldRing(emerald: Color, left: Float) {
    Canvas(Modifier.size(280.dp)) {
        val w = 2.dp.toPx()
        val inset = 6.dp.toPx()
        val box = Size(size.width - 2 * inset, size.height - 2 * inset)
        drawArc(Color(0xFF2E3538), 0f, 360f, false, Offset(inset, inset), box, style = Stroke(w))
        val sweep = 360f * left
        drawArc(emerald, -90f, sweep, false, Offset(inset, inset), box, style = Stroke(w * 1.5f, cap = StrokeCap.Round))
        val a = Math.toRadians((-90f + sweep).toDouble()).toFloat()
        val rr = box.width / 2
        drawCircle(emerald, 4.dp.toPx(), Offset(size.width / 2 + cos(a) * rr, size.height / 2 + sin(a) * rr))
    }
}
