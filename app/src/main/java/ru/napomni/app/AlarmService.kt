package ru.napomni.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Звон будильника и «эффекты» обычного напоминания (голос, вспышка). Отдельная служба, потому что
 * звук должен идти дольше, чем живёт приёмник будильника. Будильник звенит [RING_MS]; дальше
 * уведомление остаётся, а при «повторять» через 5 минут зазвенит снова.
 */
class AlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var tts: TextToSpeech? = null
    private var torchId: String? = null
    private var torchOn = false
    private var wake: PowerManager.WakeLock? = null
    private var active = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getIntExtra(EXTRA_ID, -1) ?: -1
        val ring = intent?.getBooleanExtra(EXTRA_RING, false) ?: false
        val r = Store.get(this, id)
        if (r == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val now = System.currentTimeMillis()
        val t = Trigger(intent?.getLongExtra(EXTRA_AT, now) ?: now, Kind.EVENT)
        val n = Notifier.build(this, r, t, now, ring = ring, quiet = true)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(r.id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(r.id, n)
            }
        } catch (e: Exception) {
            // Android не дал запустить службу (например, сразу после загрузки) — останется уведомление.
            stopSelf()
            return START_NOT_STICKY
        }
        stopEffects()
        active = true
        ringing = if (ring) r.id else null
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "napomni:alarm")
            .apply { acquire(RING_MS + 30_000) }

        if (ring) {
            if (r.speak) speak(r.title, alarm = true) { startRinging(r) } else startRinging(r)
            if (r.flash) blink(-1)
            handler.postDelayed({ finish() }, RING_MS)
        } else {
            // Сначала страховочный срок: голос или фонарик могут не ответить.
            handler.postDelayed({ finish() }, 20_000)
            var left = (if (r.speak) 1 else 0) + (if (r.flash) 1 else 0)
            val one = { if (--left == 0) finish() }
            if (r.speak) speak(r.title, alarm = false, then = one)
            if (r.flash) blink(FLASHES, then = one)
            if (left == 0) finish()
        }
        return START_NOT_STICKY
    }

    private fun attrs(alarm: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun startRinging(r: Reminder) {
        val candidates = listOfNotNull(
            r.melody?.let { Uri.parse(it) },
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        player = candidates.firstNotNullOfOrNull { play(it) }
        vibrator = vibrator().also {
            @Suppress("DEPRECATION")
            it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 700), 0), attrs(alarm = true))
        }
    }

    private fun play(uri: Uri): MediaPlayer? {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(attrs(alarm = true))
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            mp
        } catch (e: Exception) {
            mp.release()
            null
        }
    }

    private fun vibrator(): Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Vibrator::class.java)
    }

    /** Прочитать вслух. Нет русского голоса или движок молчит — не ждём, идём дальше. */
    private fun speak(text: String, alarm: Boolean, then: () -> Unit) {
        var called = false
        val next = {
            if (!called) {
                called = true
                handler.post(then)
            }
        }
        handler.postDelayed({ next() }, 10_000)
        tts = TextToSpeech(this) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null ||
                engine.setLanguage(Locale.forLanguageTag("ru-RU")) < TextToSpeech.LANG_AVAILABLE
            ) {
                next()
                return@TextToSpeech
            }
            engine.setAudioAttributes(attrs(alarm))
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = next()

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = next()
            })
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "napomni")
        }
    }

    /** Мигать фонариком: times < 0 — пока звенит. */
    private fun blink(times: Int, then: () -> Unit = {}) {
        val cm = getSystemService(CameraManager::class.java)
        val id = try {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        } catch (e: Exception) {
            null
        }
        if (id == null) {
            then()
            return
        }
        torchId = id
        var count = 0
        val step = object : Runnable {
            override fun run() {
                torchOn = !torchOn
                try {
                    cm.setTorchMode(id, torchOn)
                } catch (e: Exception) {
                    then()
                    return
                }
                if (!torchOn) count++
                if (times < 0 || count < times) handler.postDelayed(this, 350) else then()
            }
        }
        handler.post(step)
    }

    private fun stopEffects() {
        handler.removeCallbacksAndMessages(null)
        player?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
        tts?.let {
            it.stop()
            it.shutdown()
        }
        tts = null
        torchId?.let {
            try {
                getSystemService(CameraManager::class.java).setTorchMode(it, false)
            } catch (_: Exception) {
            }
        }
        torchId = null
        torchOn = false
        wake?.let { if (it.isHeld) it.release() }
        wake = null
    }

    /** Звон кончился, а уведомление с кнопками остаётся. */
    private fun finish() {
        if (!active) return
        active = false
        stopEffects()
        ringing = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onDestroy() {
        active = false
        stopEffects()
        ringing = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_RING = "ring"
        private const val EXTRA_AT = "at"
        const val RING_MS = 60_000L
        private const val FLASHES = 8

        /** Какое напоминание сейчас звенит — экран будильника не запускает звон второй раз. */
        @Volatile
        var ringing: Int? = null
            private set

        fun start(ctx: Context, id: Int, at: Long, ring: Boolean) {
            val i = Intent(ctx, AlarmService::class.java)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_RING, ring).putExtra(EXTRA_AT, at)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                // Запуск из фона запрещён — звон начнёт экран будильника, когда откроется.
            }
        }

        fun stop(ctx: Context, id: Int) {
            if (ringing == null || ringing == id) ctx.stopService(Intent(ctx, AlarmService::class.java))
        }
    }
}
