package com.watermonitor.app.data.history

import org.junit.Assert.*
import org.junit.Test

class ArchiveCalendarTest {
    @Test fun monthsUseManilaEvenWhenThePhoneUsesAnotherTimezone() {
        val end = ArchiveCalendar.end("2028-02")
        assertEquals("2028-02", ArchiveCalendar.month(end - 1))
        assertEquals("2028-03", ArchiveCalendar.month(end))
        assertEquals(29L * 86_400_000, end - ArchiveCalendar.start("2028-02"))
    }
}
