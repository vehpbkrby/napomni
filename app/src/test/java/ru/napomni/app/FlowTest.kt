package ru.napomni.app

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Функциональные тесты: настоящий экран создания и настройки, действия как у человека —
 * набрать текст, нажать кнопки, — и проверка, какое напоминание (заготовка) сохранилось.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlowTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<*>? = null

    @Before
    fun clean() {
        File(ctx.filesDir, "reminders.json").delete()
        File(ctx.filesDir, "templates.json").delete()
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        Store.resetForTests()
        Templates.resetForTests()
    }

    @After
    fun close() {
        scenario?.close()
    }

    private fun openNew() {
        scenario = ActivityScenario.launch<QuickAddActivity>(Intent(ctx, QuickAddActivity::class.java))
        rule.waitForIdle()
    }

    /**
     * Нажать. Через действие нажатия узла: в Robolectric касание в полупрозрачное окно листа уходит мимо,
     * а обработчик у кнопки тот же, что и от пальца.
     */
    private fun tap(text: String) {
        rule.onAllNodesWithText(text).onFirst().performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun type(text: String) {
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput(text)
        rule.waitForIdle()
    }

    private fun saved(): List<Reminder> = Store.all(ctx)

    private fun local(t: Long) = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(t), ZoneId.systemDefault())

    @Test
    fun completeTemplateCreatesReminderWithOneTap() {
        openNew()
        val before = System.currentTimeMillis()
        tap("Через 3 мин")
        val r = saved().single()
        assertEquals("Через 3 мин", r.title)
        assertTrue(r.at - before in 3 * MINUTE - 5_000..3 * MINUTE + 10_000)
    }

    @Test
    fun typingDoesNotCollapseTheField() {
        openNew()
        "Чайник".forEach { type(it.toString()) }
        // Поле на месте и в нём весь текст; выбор «когда» виден сразу.
        rule.onNode(hasSetTextAction() and hasText("Чайник")).assertIsFocused()
        rule.onNodeWithText("В ближайшие сутки").assertExists()
        rule.onNodeWithText("В конкретные дни").assertExists()
    }

    @Test
    fun inFifteenMinutes() {
        openNew()
        type("Позвонить маме")
        val t0 = System.currentTimeMillis()
        tap("В ближайшие сутки")
        tap("15 мин")
        tap("Напомнить")
        val r = saved().single()
        assertEquals("Позвонить маме", r.title)
        assertTrue(r.at - t0 in 15 * MINUTE - 5_000..15 * MINUTE + 15_000)
        // Картинка подобрана по тексту.
        assertEquals("📞", r.icon)
    }

    @Test
    fun dateFromTextThenOneDayBefore() {
        openNew()
        type("Врач завтра в 15:30")
        // Дата понята из текста — «за сколько» видно сразу, без выбора «когда».
        tap("За 1 день")
        tap("Напомнить")
        val r = saved().single()
        assertEquals("Врач", r.title)
        assertEquals(LocalDate.now().plusDays(1).atTime(15, 30), local(r.at))
        assertEquals(listOf(1), r.days)
    }

    @Test
    fun recurringMonthlyStaysOpenAndSaves() {
        openNew()
        type("Оплатить интернет")
        tap("Повторяющееся событие")
        // Открылся календарь — берём предложенную дату и время.
        tap("Готово")
        tap("Каждый месяц")
        // Блок не свернулся: выбор периодичности на месте.
        rule.onNodeWithText("Каждый день").assertExists()
        tap("Напомнить")
        val r = saved().single()
        assertEquals(ru.napomni.app.Rule.MONTHLY, r.rule)
        assertEquals(LocalTime.of(12, 0), local(r.at).toLocalTime())
    }

    @Test
    fun specificDays() {
        openNew()
        type("Полить цветы")
        tap("В конкретные дни")
        val d1 = LocalDate.now().plusDays(1)
        val d2 = LocalDate.now().plusDays(2)
        tap("${Fmt.WEEKDAYS[d1.dayOfWeek.value - 1]} ${d1.dayOfMonth}")
        tap("${Fmt.WEEKDAYS[d2.dayOfWeek.value - 1]} ${d2.dayOfMonth}")
        tap("Готово")
        tap("Напомнить")
        val r = saved().single()
        assertEquals(listOf(d1.toEpochDay(), d2.toEpochDay()), r.dates)
        // Время не задавали — 12:00.
        assertEquals(12 * 60, r.dayTime)
    }

    @Test
    fun newTemplateInSettings() {
        scenario = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java))
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Настройки").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        tap("+ Новая заготовка")
        type("Вынести мусор")
        tap("Через — сразу")
        tap("30 мин")
        tap("Сохранить")
        val t = Templates.all(ctx).single { it.title == "Вынести мусор" }
        assertEquals(30, t.inMin)
        assertTrue(t.complete)
    }

    @Test
    fun alertTogglesCombineFreely() {
        openNew()
        type("Забрать посылку")
        tap("В ближайшие сутки")
        tap("30 мин")
        // Как напомнить: набор «Тихо», затем включаем только вибрацию и вспышку.
        tap("Тихо")
        tap("Вибрация")
        tap("Вспышка")
        rule.onNodeWithText("Свои").assertExists()
        tap("Напомнить")
        val r = saved().single()
        assertEquals(Way(sound = false, vibrate = true, flash = true), r.way)
    }

    @Test
    fun recurringFoldedOnMainScreen() {
        Store.add(ctx) { id ->
            Reminder(id, "Оплатить интернет", at = System.currentTimeMillis() + 86_400_000, rule = ru.napomni.app.Rule.MONTHLY, handledUntil = System.currentTimeMillis())
        }
        Store.add(ctx) { id -> Reminder(id, "Чайник", at = System.currentTimeMillis() + 600_000, handledUntil = System.currentTimeMillis()) }
        scenario = ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java))
        rule.waitForIdle()
        // Список ленивый — сперва докрутить до строки, потом проверять.
        val list = rule.onNode(hasScrollAction())
        // Разовое видно сразу, повторяющееся — под свёрнутой строкой.
        list.performScrollToNode(hasText("Чайник"))
        rule.onNodeWithText("Чайник").assertExists()
        list.performScrollToNode(hasText("Повторяющиеся (1)"))
        rule.onNodeWithText("Оплатить интернет").assertDoesNotExist()
        rule.onNodeWithText("Повторяющиеся (1)").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        list.performScrollToNode(hasText("Оплатить интернет"))
        rule.onNodeWithText("Оплатить интернет").assertExists()
    }

    @Test
    fun soonByClockWithinADay() {
        openNew()
        type("Забрать ребёнка")
        tap("В ближайшие сутки")
        tap("К часу")
        // Предложен ближайший целый час — сработает в пределах суток.
        tap("Готово")
        tap("Напомнить")
        val r = saved().single()
        val now = System.currentTimeMillis()
        assertTrue(r.at > now && r.at <= now + 24 * 60 * MINUTE)
        assertEquals(0, local(r.at).minute)
    }

    @Test
    fun speakAskedByDefaultAndNormalPreset() {
        openNew()
        type("Анализы")
        tap("В ближайшие сутки")
        tap("1 ч")
        // По умолчанию — «Обычно»; голос включаем, но без галочки он только по кнопке.
        tap("Читать вслух")
        rule.onNodeWithText("сразу, без подтверждения").assertExists()
        tap("Напомнить")
        val r = saved().single()
        assertEquals(Way(sound = true, vibrate = true, speak = true, speakAuto = false), r.way)
    }

    @Test
    fun templatesHideOnceYouStart() {
        openNew()
        rule.onNodeWithText("Через 3 мин").assertExists()
        type("Свой текст")
        rule.onNodeWithText("Через 3 мин").assertDoesNotExist()
    }

    @Test
    fun builtinMelodyByDefault() {
        openNew()
        type("Таблетки")
        tap("В ближайшие сутки")
        tap("15 мин")
        // Без выбора звучат встроенные сигналы точного времени.
        rule.onNodeWithText(Melody.BUILTIN_NAME).assertExists()
        tap("Напомнить")
        assertEquals(null, saved().single().melody)
    }
}
