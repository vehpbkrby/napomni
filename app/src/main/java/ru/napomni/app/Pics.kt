package ru.napomni.app

import androidx.compose.ui.graphics.Color

/**
 * Картинки к напоминаниям: эмодзи на мягкой цветной подложке. Подбираются по словам в тексте
 * («врач» → 🩺), человек может сменить. Работают без интернета и без своих файлов.
 */
object Pics {
    data class Pic(val emoji: String, val tint: Long, val words: List<String>)

    // Подложки — приглушённые, под «латунь» и «изумруд»; в тёмной теме идут с прозрачностью.
    private const val SAND = 0xFFE9DCC4
    private const val SKY = 0xFFD8E3EE
    private const val ROSE = 0xFFE8D3D0
    private const val SAGE = 0xFFD9E6D6
    private const val LILAC = 0xFFE6DDEE
    private const val HONEY = 0xFFF0E3C8

    val ALL = listOf(
        Pic("🩺", ROSE, listOf("врач", "доктор", "больниц", "поликлин", "анализ", "приём", "прием", "стомат", "зуб")),
        Pic("💊", ROSE, listOf("лекарств", "таблет", "пилюл", "витамин", "капл", "укол")),
        Pic("🎂", LILAC, listOf("др ", "день рожд", "рождени", "юбилей", "годовщ", "праздн")),
        Pic("📞", SAND, listOf("позвон", "звонок", "созвон", "набрать", "перезвон")),
        Pic("💳", SAGE, listOf("оплат", "заплат", "платёж", "платеж", "счёт", "счет", "кредит", "налог", "аренд")),
        Pic("🫖", SKY, listOf("чайник", "чай", "кофе")),
        Pic("🍳", HONEY, listOf("плит", "духовк", "готов", "варит", "суп", "ужин", "обед", "завтрак")),
        Pic("🛒", HONEY, listOf("купить", "магазин", "покуп", "продукт")),
        Pic("📦", SAND, listOf("посылк", "забрать", "доставк", "почт", "пункт", "озон", "вайлдб")),
        Pic("🌱", SAGE, listOf("цвет", "полить", "растен", "сад", "дач")),
        Pic("🚗", SKY, listOf("машин", "авто", "шиномонт", "сервис", "заправ", "парков", "то ")),
        Pic("✈️", LILAC, listOf("самолёт", "самолет", "рейс", "аэропорт", "поезд", "вокзал", "билет", "поездк")),
        Pic("🏋️", HONEY, listOf("трениров", "спорт", "зал", "бег", "йог", "бассейн")),
        Pic("💼", SKY, listOf("встреч", "совещ", "работ", "планёр", "планер", "отчёт", "отчет", "созвон по")),
        Pic("📄", SAND, listOf("документ", "паспорт", "справк", "заявлен", "подать", "подпис")),
        Pic("🐾", ROSE, listOf("кот", "собак", "ветерин", "корм", "выгул")),
        Pic("👶", LILAC, listOf("ребён", "ребен", "детск", "сад ", "школ", "забрать сына", "забрать доч")),
        Pic("🧺", SKY, listOf("стирк", "бельё", "белье", "убор", "пылесос")),
        Pic("⏱", HONEY, emptyList()),
        Pic("🔔", SAND, emptyList()),
    )

    const val DEFAULT = "🔔"

    fun of(emoji: String?): Pic = ALL.firstOrNull { it.emoji == emoji } ?: ALL.first { it.emoji == DEFAULT }

    /** Картинка по словам в тексте; null — ничего не подошло. */
    fun guess(text: String): String? {
        val s = " " + text.lowercase().replace('ё', 'е') + " "
        return ALL.firstOrNull { p -> p.words.any { w -> s.contains(w.replace('ё', 'е')) } }?.emoji
    }

    fun tint(emoji: String?, dark: Boolean): Color =
        Color(of(emoji).tint).let { if (dark) it.copy(alpha = .22f) else it }
}
