package dev.ocusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class UsageApiTest {
    private val sample = """
        {"usage":{
          "rolling":{"status":"ok","percent":2,"resetsAt":"2026-11-03T15:30:00Z"},
          "weekly":{"status":"ok","percent":41.5,"resetsAt":"2026-11-07T00:00:00Z"},
          "monthly":{"status":"rate_limited","percent":100,"resetsAt":null}
        }}
    """.trimIndent()

    @Test fun parsesEnvelope() {
        val u = UsageApi.parse(sample)
        assertEquals(2.0, u.rolling.percent, 0.0)
        assertEquals(41.5, u.weekly.percent, 0.0)
        assertEquals(Instant.parse("2026-11-03T15:30:00Z"), u.rolling.resetsAt)
        assertNull(u.monthly.resetsAt)
        assertFalse(u.rolling.exceeded)
        assertTrue(u.monthly.exceeded)
    }

    @Test fun parsesWithoutEnvelope() {
        val u = UsageApi.parse("""{"rolling":{"percent":5},"weekly":{"percent":6},"monthly":{"percent":7}}""")
        assertEquals(7.0, u.monthly.percent, 0.0)
        assertFalse(u.monthly.exceeded)
    }

    @Test(expected = UsageException::class) fun rejectsMissingWindow() {
        UsageApi.parse("""{"usage":{"rolling":{"percent":5}}}""")
    }

    @Test(expected = UsageException::class) fun rejectsNonJson() {
        UsageApi.parse("<html>")
    }

    @Test fun resetText() {
        val now = Instant.parse("2026-11-03T13:15:00Z")
        val m = Meter(10.0, "ok", Instant.parse("2026-11-03T15:30:00Z"))
        assertTrue(m.resetText(now).startsWith("Resets in 2h 15m"))
        val w = Meter(10.0, "ok", Instant.parse("2026-11-06T15:15:00Z"))
        assertTrue(w.resetText(now).startsWith("Resets in 3d 2h"))
        assertEquals("Reset time unknown", Meter(1.0, "ok", null).resetText(now))
        assertEquals("Resetting now", Meter(1.0, "ok", now.minusSeconds(5)).resetText(now))
    }
}
