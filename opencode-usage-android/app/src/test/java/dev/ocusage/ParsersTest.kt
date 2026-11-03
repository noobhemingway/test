package dev.ocusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class ParsersTest {
    private val now = Instant.parse("2026-11-03T13:15:00Z")

    // ---- OpenCode Go API ----

    @Test fun openCode() {
        val s = Parsers.openCode("""
            {"usage":{
              "rolling":{"status":"ok","percent":2,"resetsAt":"2026-11-03T15:30:00Z"},
              "weekly":{"status":"ok","percent":41.5,"resetsAt":"2026-11-07T00:00:00Z"},
              "monthly":{"status":"rate_limited","percent":100,"resetsAt":null}}}
        """, now)
        assertEquals(listOf("5-hour", "Weekly", "Monthly"), s.meters.map { it.label })
        assertEquals(41.5, s.meters[1].percent, 0.0)
        assertEquals(Instant.parse("2026-11-03T15:30:00Z"), s.meters[0].resetsAt)
        assertNull(s.meters[2].resetsAt)
        assertTrue(s.meters[2].exceeded)
    }

    @Test(expected = UsageException::class) fun openCodeMissingWindow() {
        Parsers.openCode("""{"usage":{"rolling":{"percent":5}}}""", now)
    }

    @Test(expected = UsageException::class) fun rejectsNonJson() {
        Parsers.openCode("<html>", now)
    }

    // ---- Claude ----

    @Test fun claude() {
        val s = Parsers.claude("""
            {"usage":{"five_hour":{"utilization":12.0,"resets_at":"2026-11-03T15:00:00.123456+00:00"},
                      "seven_day":{"utilization":40,"resets_at":"2026-11-08T09:00:00+00:00"},
                      "seven_day_opus":null}}
        """, now)
        assertEquals(listOf("5-hour", "Weekly"), s.meters.map { it.label })
        assertEquals(12.0, s.meters[0].percent, 0.0)
        assertEquals(Instant.parse("2026-11-03T15:00:00.123456Z"), s.meters[0].resetsAt)
        assertEquals(40.0, s.meters[1].percent, 0.0)
    }

    @Test fun claudeUnusedWindow() {
        val s = Parsers.claude("""{"usage":{"five_hour":null,"seven_day":{"utilization":3,"resets_at":null}}}""", now)
        assertEquals(0.0, s.meters[0].percent, 0.0)
        assertEquals("Starts on first use", s.meters[0].resetText(now))
    }

    @Test(expected = UsageException::class) fun claudeNoWindows() {
        Parsers.claude("""{"usage":{"error":"x"}}""", now)
    }

    // ---- Codex ----

    @Test fun codex() {
        val s = Parsers.codex("""
            {"rate_limit":{
              "primary_window":{"used_percent":23,"limit_window_seconds":18000,"reset_after_seconds":3600,"reset_at":1793719200},
              "secondary_window":{"used_percent":61,"limit_window_seconds":604800,"reset_after_seconds":200000}},
             "plan_type":"plus"}
        """, now)
        assertEquals(listOf("5-hour", "Weekly"), s.meters.map { it.label })
        assertEquals(23.0, s.meters[0].percent, 0.0)
        assertEquals(Instant.ofEpochSecond(1793719200), s.meters[0].resetsAt)
        assertEquals(now.plusSeconds(200000), s.meters[1].resetsAt)
    }

    @Test(expected = UsageException::class) fun codexEmpty() {
        Parsers.codex("""{"rate_limit":null}""", now)
    }

    // ---- Grok (page text) ----

    @Test fun grok() {
        val s = Parsers.grok("Settings\nAccount\nUsage\nSuperGrok\nWeekly usage\n42% used\nResets in 3d 4h\nManage", now)
        assertEquals(42.0, s.meters[0].percent, 0.0)
        assertEquals(now.plus(Duration.ofDays(3).plusHours(4)), s.meters[0].resetsAt)
    }

    @Test fun grokResetDateOnly() {
        val s = Parsers.grok("Usage this week 7.5% used\nResets on Mon, Nov 10", now)
        assertEquals(7.5, s.meters[0].percent, 0.0)
        assertNull(s.meters[0].resetsAt)
        assertEquals("Resets on Mon, Nov 10", s.meters[0].resetText(now))
    }

    @Test(expected = UsageException::class) fun grokNoUsage() {
        Parsers.grok("Ask Grok anything\nUpgrade to SuperGrok 50% off", now)
    }

    // ---- OpenCode console (text of the Activity page, from the screenshot) ----

    private val consoleText = """
        Activity
        My usage
        Usage
        Logs
        Audit
        18%
        Go usage · Renews Dec 3
        150%
        100%
        50%
        0%
        NOV 3
        NOV 12
        NOV 22
        DEC 3
        Usage
        Monthly limit
        Logs
        Last 40 requests
        200
        iceman-contributor-free · noobhemi...
        10h ago
        200
        iceman-contributor-free · noobhemi...
        10h ago
        Limits
        When your Go usage resets
        Rolling
        Starts on first use
        0% used
        Weekly
        Resets in 5d 7h
        35% used
        Monthly
        Resets in 29d 10h
        18% used
        Models
        Most used this period
        GO
        Grok 4.7
        78%
        GO
        Iceman Contributor Free
        17%
        GO
        GPT-6 Luna
        5%
        Requests
        58
        Tokens
        4.3M
        Extra usage
        Off · Requests pause at a limit
        Available credit
        ${'$'}0.00
        Keep working past a limit · Turn on in Go
    """.trimIndent()

    @Test fun console() {
        val d = Parsers.console(consoleText, now)
        assertEquals(18.0, d.goPercent!!, 0.0)
        assertEquals("Dec 3", d.renews)
        assertEquals(listOf("5-hour", "Weekly", "Monthly"), d.limits.map { it.label })
        assertEquals(listOf(0.0, 35.0, 18.0), d.limits.map { it.percent })
        assertEquals("Starts on first use", d.limits[0].resetText(now))
        assertEquals(now.plus(Duration.ofDays(5).plusHours(7)), d.limits[1].resetsAt)
        assertEquals(
            listOf("Grok 4.7" to 78.0, "Iceman Contributor Free" to 17.0, "GPT-6 Luna" to 5.0),
            d.models,
        )
        assertEquals("58", d.requests)
        assertEquals("4.3M", d.tokens)
        assertEquals("Off · Requests pause at a limit", d.extraStatus)
        assertEquals("$0.00", d.credit)
    }

    @Test fun consoleCompactLines() {
        // Same page if the browser joins some lines together.
        val text = """
            18% Go usage · Renews Dec 3
            Limits
            When your Go usage resets
            Rolling Starts on first use 0% used
            Weekly Resets in 5d 7h 35% used
            Monthly Resets in 29d 10h 18% used
            Models
            Most used this period
            Grok 4.7 78%
            GPT-6 Luna 5%
            Requests 58
            Tokens 4.3M
        """.trimIndent()
        val d = Parsers.console(text, now)
        assertEquals(18.0, d.goPercent!!, 0.0)
        assertEquals(listOf(0.0, 35.0, 18.0), d.limits.map { it.percent })
        assertEquals(listOf("Grok 4.7" to 78.0, "GPT-6 Luna" to 5.0), d.models)
        assertEquals("58", d.requests)
        assertEquals("4.3M", d.tokens)
        assertNull(d.credit)
    }

    @Test(expected = UsageException::class) fun consoleLoginPage() {
        Parsers.console("Sign in to OpenCode\nContinue with GitHub\nContinue with Google", now)
    }

    @Test fun resetText() {
        val m = Meter("5-hour", 10.0, resetsAt = Instant.parse("2026-11-03T15:30:00Z"))
        assertTrue(m.resetText(now).startsWith("Resets in 2h 15m"))
        assertEquals("Reset time unknown", Meter("x", 1.0).resetText(now))
        assertEquals("Resetting now", Meter("x", 1.0, resetsAt = now.minusSeconds(5)).resetText(now))
        assertFalse(m.exceeded)
    }
}
