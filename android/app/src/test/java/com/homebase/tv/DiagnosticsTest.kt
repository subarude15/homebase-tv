package com.homebase.tv

import com.homebase.tv.data.backend.Health
import com.homebase.tv.data.backend.backendJson
import com.homebase.tv.ui.ConnectionStatus
import com.homebase.tv.ui.statusDetail
import com.homebase.tv.ui.statusHeadline
import com.homebase.tv.ui.uptimeText
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsTest {
    private val checkedAt = Instant.parse("2026-10-30T12:57:03Z").toEpochMilli()

    private fun ok(health: Health) = ConnectionStatus.Ok(health, checkedAt)

    @Test
    fun parsesHealthWithAndWithoutUptime() {
        val json = """{"status":"ok","version":"1.4.0","uptime_seconds":93784,"database":true,"authenticated":true}"""
        assertEquals(93_784L, backendJson.decodeFromString(Health.serializer(), json).uptimeSeconds)
        val old = """{"status":"ok","version":"1.0.0","database":true,"authenticated":true}"""
        assertNull(backendJson.decodeFromString(Health.serializer(), old).uptimeSeconds)
    }

    @Test
    fun healthyShowsEveryDetail() {
        val status = ok(Health("ok", "1.4.0", database = true, authenticated = true, uptimeSeconds = 93_784))
        assertEquals("Backend healthy", statusHeadline(status))
        assertEquals(
            "Version 1.4.0 · Database OK · Token accepted · Up 1d 2h · Checked just now",
            statusDetail(status, checkedAt),
        )
    }

    @Test
    fun rejectedTokenAndDegradedDatabase() {
        val unauthenticated = ok(Health("ok", "1.4.0", database = true, authenticated = false))
        assertEquals("Connected, but the API token was rejected", statusHeadline(unauthenticated))
        assertEquals(
            "Version 1.4.0 · Database OK · Token rejected · Checked just now",
            statusDetail(unauthenticated, checkedAt + 4_999),
        )
        val degraded = ok(Health("degraded", "1.4.0", database = false, authenticated = true, uptimeSeconds = 42))
        assertEquals("Backend degraded: database unhealthy", statusHeadline(degraded))
        assertEquals(
            "Version 1.4.0 · Database unhealthy · Token accepted · Up 42s · Checked 5s ago",
            statusDetail(degraded, checkedAt + 5_000),
        )
        assertEquals("Backend degraded", statusHeadline(ok(Health("degraded", "1.4.0", true, true))))
    }

    @Test
    fun failureKeepsMessageAndCheckedAge() {
        val failed = ConnectionStatus.Failed("timeout", checkedAt)
        assertEquals("Can't reach backend", statusHeadline(failed))
        assertEquals("timeout · Checked 2m ago", statusDetail(failed, checkedAt + 150_000))
        assertEquals("timeout · Checked 1h 1m ago", statusDetail(failed, checkedAt + 3_660_000))
        assertEquals("Checking backend…", statusHeadline(ConnectionStatus.Testing))
        assertEquals("", statusDetail(ConnectionStatus.Testing, checkedAt))
    }

    @Test
    fun clockBehindCheckIsJustNow() {
        assertEquals("timeout · Checked just now", statusDetail(ConnectionStatus.Failed("timeout", checkedAt), checkedAt - 60_000))
    }

    @Test
    fun uptimeKeepsTwoLargestUnits() {
        assertEquals("0s", uptimeText(0))
        assertEquals("59s", uptimeText(59))
        assertEquals("5m", uptimeText(330))
        assertEquals("2h 0m", uptimeText(7_200))
        assertEquals("3d 0h", uptimeText(3 * 86_400 + 59))
    }
}
