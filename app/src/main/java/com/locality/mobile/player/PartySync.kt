package com.locality.mobile.player

import com.locality.mobile.party.PartyPlayback

/**
 * Keeping two players on the same frame — the phone's half of the desktop's
 * src/partySync.ts. The constants matter more than the code: they're what make
 * a phone and a PC behave identically in the same party, so change them in both
 * places or not at all.
 *
 * Only explicit ACTIONS are exchanged, never the ordinary tick of playback. But
 * receiving an action is not enough on its own: a guest that spends two seconds
 * buffering after a seek would stay two seconds behind for the rest of the film,
 * because nothing would ever look again. So each action sets an ANCHOR, and a
 * slow local loop keeps the player matched to it (see PlayerActivity).
 */

/** Difference worth correcting. Below this, seeking is more jarring than the drift. */
const val SYNC_TOLERANCE_S = 1.0

/**
 * How long after applying a remote action we stay quiet. Applying it makes the
 * player fire its own play/pause/seek callbacks, which would publish straight
 * back out and ping-pong between the two devices.
 */
const val ECHO_QUIET_MS = 900L

/**
 * Grace period after a player opens. Autoplay and a resume both fire before the
 * user has touched anything; publishing those would mean the last person to
 * open their player drags everyone else to wherever they started.
 */
const val SETTLE_MS = 1800L

/** How often the local loop re-checks that we're still where the party is. */
const val RECONCILE_MS = 1000L

/**
 * Minimum gap between two corrective seeks.
 *
 * A guest on a slow link can buffer for longer than the drift we're correcting,
 * so back-to-back corrections would chase their own tail: seek, buffer, fall
 * behind, seek again. Correcting at a walking pace converges instead.
 */
const val RESEEK_COOLDOWN_MS = 4000L

/**
 * Assumed age of an action when it reaches us: half the party poll interval
 * (700 ms) plus a little network. Used once, at receipt; everything after that
 * is measured with the local clock, so the two devices' clocks never come into
 * it — which is the whole point, since a PC and a phone are often seconds apart.
 */
const val ACTION_LATENCY_S = 0.4

/**
 * Where the party is *right now*, measured from when we received the action.
 *
 * [nowMs] is this device's clock, and [pb].receivedAt was stamped with the same
 * clock, so their difference is real elapsed time regardless of how wrong
 * either device's idea of "now" is in absolute terms.
 */
fun partyPositionNow(pb: PartyPlayback, nowMs: Long): Double {
    if (pb.paused) return pb.position
    val anchoredAt = if (pb.receivedAt > 0) pb.receivedAt else nowMs
    val elapsed = (nowMs - anchoredAt) / 1000.0
    return pb.position + ACTION_LATENCY_S + elapsed.coerceAtLeast(0.0)
}
