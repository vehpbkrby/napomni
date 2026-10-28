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
        // Голос и вспышка — только у главного срабатывания; «заранее» — звук и вибрация, как выбрано.
        val main = intent?.getBooleanExtra(EXTRA_MAIN, true) ?: true
        val onlySpeak = intent?.getBooleanExtra(EXTRA_SPEAK, false) ?: false
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
            if (r.speak && r.speakAuto) speak(r.title, alarm = true) { startRinging(r) } else startRinging(r)
            if (r.flash) blink(-1)
            handler.postDelayed({ finish() }, RING_MS)
        } else {
            // Сначала страховочный срок: мелодия, голос или фонарик могут не ответить.
            handler.postDelayed({ finish() }, 20_000)
            // «Прочитать» из уведомления — только голос; иначе голос сам — лишь если разрешено без подтверждения.
            if (onlySpeak) {
                handler.postDelayed({ finish() }, 20_000)
                speak(r.title, alarm = false) { finish() }
                return START_NOT_STICKY
            }
            val voice = r.speak && r.speakAuto && main
            val fl = r.flash && main
            var left = (if (r.sound || voice) 1 else 0) + (if (r.vibrate) 1 else 0) + (if (fl) 1 else 0)
            val one = { if (--left == 0) finish() }
            // Звук и голос — по очереди: сначала мелодия уведомления, потом текст вслух.
            val talk = { if (voice) speak(r.title, alarm = false, then = one) else one() }
            if (r.sound) playOnce(r) { talk() } else if (voice) talk()
            if (r.vibrate) {
                vibrator = vibrator().also {
                    @Suppress("DEPRECATION")
                    it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 450, 250, 450), -1), attrs(alarm = false))
                }
                handler.postDelayed({ one() }, 1_400)
            }
            if (fl) blink(FLASHES, then = one)
            if (left == 0) finish()
        }
        return START_NOT_STICKY
    }

    private fun attrs(alarm: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** «Как будильник»: мелодия по кругу (если включён звук) и вибрация по кругу (если включена). */
    private fun startRinging(r: Reminder) {
        if (r.sound) {
            val candidates = listOfNotNull(
                Melody.uri(this, r.melody),
                Settings.System.DEFAULT_ALARM_ALERT_URI,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            )
            player = candidates.firstNotNullOfOrNull { play(it) }
        }
        if (r.vibrate) vibrator = vibrator().also {
            @Suppress("DEPRECATION")
            it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 700), 0), attrs(alarm = true))
        }
    }

    /** Обычный звук: мелодия один раз на громкости уведомлений; [then] — когда доиграла (или не вышло). */
    private fun playOnce(r: Reminder, done: () -> Unit) {
        // Конец мелодии и страховочный обрыв могут прийти оба — сообщаем один раз.
        var called = false
        val then = {
            if (!called) {
                called = true
                done()
            }
        }
        val candidates = listOfNotNull(
            Melody.uri(this, r.melody),
            Settings.System.DEFAULT_NOTIFICATION_URI,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        for (uri in candidates) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attrs(alarm = false))
                mp.setDataSource(this, uri)
                mp.isLooping = false
                mp.setOnCompletionListener { handler.post(then) }
                mp.prepare()
                mp.start()
                player = mp
                // Длинную мелодию обрываем через 8 с — это уведомление, не будильник.
                handler.postDelayed({
                    if (player === mp) try { mp.stop() } catch (_: Exception) {}
                    then()
                }, 8_000)
                return
            } catch (e: Exception) {
                mp.release()
            }
        }
        then()
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
        private const val EXTRA_MAIN = "main"
        private const val EXTRA_SPEAK = "speakOnly"

        /** Кнопка «Прочитать» в уведомлении: прочитать название вслух, по явному нажатию. */
        fun speakNow(ctx: Context, id: Int) {
            val i = Intent(ctx, AlarmService::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_SPEAK, true)
                .putExtra(EXTRA_AT, System.currentTimeMillis())
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (_: Exception) {
            }
        }
        const val RING_MS = 60_000L
        private const val FLASHES = 8

        /** Какое напоминание сейчас звенит — экран будильника не запускает звон второй раз. */
        @Volatile
        var ringing: Int? = null
            private set

        /** false — Android не дал запустить (запрет запуска из фона): звук отдаст канал уведомления. */
        fun start(ctx: Context, id: Int, at: Long, ring: Boolean, main: Boolean = true): Boolean {
            val i = Intent(ctx, AlarmService::class.java)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_RING, ring).putExtra(EXTRA_AT, at).putExtra(EXTRA_MAIN, main)
            return try {
                ContextCompat.startForegroundService(ctx, i)
                true
            } catch (e: Exception) {
                // Запуск из фона запрещён — будильник начнёт звенеть экран будильника, когда откроется.
                false
            }
        }

        fun stop(ctx: Context, id: Int) {
            if (ringing == null || ringing == id) ctx.stopService(Intent(ctx, AlarmService::class.java))
        }
    }
}
