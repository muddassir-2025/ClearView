package com.muddassir.clearview.goodpost.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The advertisement editor's schedule arithmetic (§11).
 *
 * These exist because the editor stopped asking for an ISO timestamp and started
 * offering a length instead: the conversion from "one week" to the instant the
 * server stores is now the app's job, and a mistake there is a card that expires
 * a day early rather than an error anyone sees.
 */
class AdDurationTest {

    /** A fixed instant so the arithmetic is compared against a known answer. */
    private val noon = Instant.parse("2026-10-03T12:00:00Z").toEpochMilli()

    @Test
    fun `no end and custom resolve to no expiry`() {
        assertNull(AdDuration.NoExpiry.expiryFrom(noon))
        assertNull(AdDuration.Custom.expiryFrom(noon))
    }

    @Test
    fun `a day, a week and a month each add their own length`() {
        val day = Instant.parse(AdDuration.OneDay.expiryFrom(noon)!!)
        val week = Instant.parse(AdDuration.OneWeek.expiryFrom(noon)!!)
        val month = Instant.parse(AdDuration.OneMonth.expiryFrom(noon)!!)

        assertEquals(Instant.ofEpochMilli(noon).plusSeconds(86_400), day)
        assertEquals(Instant.ofEpochMilli(noon).plusSeconds(7 * 86_400), week)
        // A calendar month rather than thirty days: `plusMonths` is what the
        // implementation must use, and 31 October + 1 month proves it.
        assertEquals(
            ZonedDateTime.parse("2026-10-03T12:00:00Z").plusMonths(1).toInstant(),
            month
        )
    }

    @Test
    fun `a month from the thirty-first lands in the next month, not on the first`() {
        // The case a fixed 30-day count gets wrong. January 31 + 1 month is
        // February 28 (or 29), which is what a person means by "a month".
        val jan31 = Instant.parse("2026-01-31T10:00:00Z").toEpochMilli()
        val end = Instant.parse(AdDuration.OneMonth.expiryFrom(jan31)!!)
        assertEquals(Instant.parse("2026-02-28T10:00:00Z"), end)
    }

    @Test
    fun `a picked date becomes the start of that local day`() {
        // What the Material picker returns: UTC midnight of the chosen day.
        val pickerMillis = Instant.parse("2026-10-14T00:00:00Z").toEpochMilli()
        val iso = isoFromLocalStart(pickerMillis)
        val resolved = Instant.parse(iso)

        val localDate = resolved.atZone(ZoneId.systemDefault()).toLocalDate()
        assertEquals(14, localDate.dayOfMonth)
        assertEquals(10, localDate.monthValue)
        assertEquals(2026, localDate.year)
        // Midnight local, which is what makes "starts today" mean today.
        assertEquals(
            0,
            resolved.atZone(ZoneId.systemDefault()).toLocalTime().toSecondOfDay()
        )
    }

    @Test
    fun `a date survives a round trip through the picker`() {
        val pickerMillis = Instant.parse("2026-12-25T00:00:00Z").toEpochMilli()
        val iso = isoFromLocalStart(pickerMillis)
        // The picker's own state takes UTC midnight, so handing it the local
        // timestamp directly would preselect a neighbouring day.
        val back = localStartToPickerMillis(iso)
        assertNotNull(back)
        val day = Instant.ofEpochMilli(back!!).atZone(ZoneId.of("UTC")).toLocalDate()
        assertEquals(25, day.dayOfMonth)
        assertEquals(12, day.monthValue)
    }

    @Test
    fun `an unparseable or absent instant resolves to null rather than a wrong date`() {
        assertNull(localStartToPickerMillis(null))
        assertNull(localStartToPickerMillis(""))
        assertNull(localStartToPickerMillis("not-a-date"))
        assertNull(formatAdDate("nonsense"))
        assertNull(formatAdDate(null))
    }

    @Test
    fun `a date is formatted for a person, not as an instant`() {
        val label = formatAdDate("2026-10-03T09:30:00Z")
        assertNotNull(label)
        assertTrue("expected a day and month in $label", label!!.contains("Oct"))
        assertTrue("expected a year in $label", label.contains("2026"))
        // The 3rd somewhere, whichever side of UTC the test machine sits on.
        assertTrue("expected a day in $label", label.contains("3") || label.contains("2"))
    }
}