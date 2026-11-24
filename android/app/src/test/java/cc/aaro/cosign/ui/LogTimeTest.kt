package cc.aaro.cosign.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** Activity times carry the day when they are not from today. */
class LogTimeTest {
    private val zone = ZoneId.of("America/Denver")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()
    private fun plain(s: String) = s.replace(' ', ' ')
    private val now = ms(2026, 11, 24, 15, 40)

    @Test fun todayIsTheTimeAlone() = assertEquals("10:52 AM", plain(logTime(ms(2026, 11, 24, 10, 52), now, zone, Locale.US)))
    @Test fun anotherDayHasItsDate() = assertEquals("Nov 19, 10:52 PM", plain(logTime(ms(2026, 11, 19, 22, 52), now, zone, Locale.US)))
    @Test fun anotherYearHasItsYear() = assertEquals("Dec 31, 2025, 9:05 PM", plain(logTime(ms(2025, 12, 31, 21, 5), now, zone, Locale.US)))
}
