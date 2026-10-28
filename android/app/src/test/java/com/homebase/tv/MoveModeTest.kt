package com.homebase.tv

import com.homebase.tv.ui.APPS_ROW
import com.homebase.tv.ui.FocusMemory
import com.homebase.tv.ui.MoveMode
import com.homebase.tv.ui.RemoteKey
import com.homebase.tv.ui.RemoteKey.Code
import com.homebase.tv.ui.on
import com.homebase.tv.ui.restoreIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoveModeTest {
    private val order = listOf("emby", "spotify", "youtube", "nuvio")

    /** Runs [keys] from a fresh Move Mode on [pkg], as a long press starts it; returns the last state and what the card saw. */
    private fun press(pkg: String, vararg keys: RemoteKey): Pair<MoveMode?, List<RemoteKey>> {
        var mode: MoveMode? = MoveMode(pkg, order)
        val cardSaw = mutableListOf<RemoteKey>()
        for (k in keys) {
            val (next, consumed) = mode!!.on(k)
            if (!consumed) cardSaw += k
            mode = next
            if (mode == null) break
        }
        return mode to cardSaw
    }

    private fun down(code: Code, repeat: Int = 0) = RemoteKey(code, down = true, repeat = repeat)
    private fun up(code: Code) = RemoteKey(code, down = false)

    @Test
    fun releasingTheLongPressDoesNotConfirmAndReachesTheCard() {
        val (mode, cardSaw) = press("youtube", down(Code.Ok, repeat = 2), up(Code.Ok))
        assertEquals(order, mode!!.order)
        // The card clears its long-click flag on this release, so the next OK after Move Mode launches.
        assertEquals(listOf(down(Code.Ok, repeat = 2), up(Code.Ok)), cardSaw)
    }

    @Test
    fun leftAndRightMoveOnePlaceAndOkSaves() {
        val (mode, _) = press("youtube", up(Code.Ok), down(Code.Right), up(Code.Right))
        assertEquals(listOf("emby", "spotify", "nuvio", "youtube"), mode!!.order)
        val moved = press("youtube", up(Code.Ok), down(Code.Left), down(Code.Left), down(Code.Left)).first!!
        assertEquals(listOf("youtube", "emby", "spotify", "nuvio"), moved.order)

        val (_, cardSaw) = press("youtube", up(Code.Ok), down(Code.Left), down(Code.Ok), up(Code.Ok))
        // OK confirmed (null) and the card never saw it, so it didn't launch YouTube.
        assertEquals(listOf(up(Code.Ok)), cardSaw)
        val confirmed = MoveMode("youtube", order).on(down(Code.Ok)).first!!.on(up(Code.Ok))
        assertEquals(null to true, confirmed)
    }

    @Test
    fun focusStaysOnTheMovedAppAndArrowsNeverReachTheCard() {
        val (mode, cardSaw) = press("emby", down(Code.Left), down(Code.Up), down(Code.Down), down(Code.Right), down(Code.Right))
        assertEquals(listOf("spotify", "youtube", "emby", "nuvio"), mode!!.order)
        assertEquals("emby", mode.pkg)
        assertEquals(emptyList<RemoteKey>(), cardSaw)
        val end = press("nuvio", down(Code.Right)).first!!
        assertEquals(order, end.order)
    }

    @Test
    fun holdingOkInMoveModeStillConfirmsOnceOnRelease() {
        val (mode, cardSaw) = press("spotify", down(Code.Right), down(Code.Ok), down(Code.Ok, repeat = 1), down(Code.Ok, repeat = 2), up(Code.Ok))
        assertNull(mode)
        assertEquals(emptyList<RemoteKey>(), cardSaw)
    }

    @Test
    fun focusReturnsToTheMovedAppAtItsNewPlace() {
        val memory = FocusMemory(APPS_ROW, "youtube", 2)
        assertEquals(3, restoreIndex(listOf("emby", "spotify", "nuvio", "youtube"), memory))
        // Cancelled or uninstalled: the same position, kept inside the row.
        assertEquals(2, restoreIndex(order, memory))
        assertEquals(1, restoreIndex(listOf("emby", "spotify"), memory))
    }
}
