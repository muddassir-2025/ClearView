package com.muddassir.clearview.goodpost.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * How long an advertisement card runs (§11).
 *
 * A length rather than a date, because that is the question an administrator is
 * actually answering: nobody decides to run a card "until the fourteenth", they
 * decide to run it "for a week". The two are the same thing once resolved, which
 * is why this carries the arithmetic and the form keeps a timestamp.
 *
 * [Custom] exists for the case the presets cannot express — a campaign that has
 * to end on a particular day — and it is what a picked date resolves to. It is
 * deliberately NOT a fixed length: it means "whatever the form's timestamp
 * currently says", and the picker is what sets that.
 */
enum class AdDuration {
    /** No end at all; the card runs until it is disabled or deleted. */
    NoExpiry,

    OneDay,
    OneWeek,
    OneMonth,

    /**
     * A start and/or end chosen from the calendar.
     *
     * The only value whose bounds do not follow from [expiryFrom], because they
     * were chosen rather than computed.
     */
    Custom;

    /**
     * When a card of this length should stop, as an ISO instant, or null for
     * [NoExpiry] and [Custom].
     *
     * A calendar month rather than thirty days: "a month" from the 31st is the
     * 30th of the next month, which is what a person means and what a fixed
     * day count would get wrong four times a year.
     */
    fun expiryFrom(nowMillis: Long): String? {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val end = when (this) {
            NoExpiry, Custom -> return null
            OneDay -> start.plusDays(1)
            OneWeek -> start.plusWeeks(1)
            OneMonth -> start.plusMonths(1)
        }
        return end.toInstant().toString()
    }
}

/** The clock, as one function so a test can pin it. */
internal fun nowMillis(): Long = System.currentTimeMillis()

/**
 * An ISO instant for the START of the local day containing [millis].
 *
 * The Material date picker answers in UTC midnight of the chosen day, which is
 * the right day only if the reader happens to live on UTC. Anchoring to the
 * LOCAL start of that day is what makes "starts today" mean today where the
 * administrator is sitting — and a card that starts at midnight local has its
 * whole first day to be seen.
 */
internal fun isoFromLocalStart(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    return day.atStartOfDay(zone).toInstant().toString()
}

/**
 * The millis the date picker should open on for an ISO instant.
 *
 * The inverse of [isoFromLocalStart], and needed because the picker's own state
 * takes UTC midnight: handing it a local timestamp would preselect the previous
 * or next day depending on which side of UTC the device is.
 */
internal fun localStartToPickerMillis(iso: String?): Long? {
    val instant = parseIsoInstant(iso) ?: return null
    val zone = ZoneId.systemDefault()
    val day = instant.atZone(zone).toLocalDate()
    return day.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
}

/** Parse an ISO instant, or null. Tolerant of an offset form, like the client's own. */
internal fun parseIsoInstant(iso: String?): Instant? {
    if (iso.isNullOrBlank()) return null
    return try {
        Instant.parse(iso)
    } catch (e: Exception) {
        try {
            ZonedDateTime.parse(iso).toInstant()
        } catch (e2: Exception) {
            null
        }
    }
}

/**
 * A short, human label for an instant — "3 Oct 2026".
 *
 * The editor shows the resolved dates beside the pills, and an ISO string is not
 * something to put in front of a person.
 */
internal fun formatAdDate(iso: String?): String? {
    val instant = parseIsoInstant(iso) ?: return null
    val date: LocalDate = instant.atZone(ZoneId.systemDefault()).toLocalDate()
    val month = MONTHS.getOrElse(date.monthValue - 1) { date.month.name.take(3) }
    return "${date.dayOfMonth} $month ${date.year}"
}

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
)
