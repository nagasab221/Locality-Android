package com.locality.mobile.player

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** What a full-width horizontal swipe seeks, per the spec (60–90 s). */
private const val FULL_SWIPE_SECONDS = 75f

enum class GestureMode { None, Volume, Brightness, Seek }

/**
 * Transparent VLC-style gesture surface:
 *  - vertical drag, right half  → volume
 *  - vertical drag, left half   → brightness
 *  - horizontal drag anywhere   → seek preview, committed on release
 *  - double-tap left/right third → exact ±10 s
 *  - plain tap                  → toggle the controls overlay
 * Only an actual drag becomes a gesture, so taps never conflict.
 */
@Composable
fun GestureLayer(
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onDoubleTapSeek: (forwardSeconds: Int) -> Unit,
    onVolumeDelta: (fractionOfScreen: Float) -> Unit,
    onBrightnessDelta: (fractionOfScreen: Float) -> Unit,
    onSeekPreview: (offsetSeconds: Int) -> Unit,
    onSeekCommit: (offsetSeconds: Int) -> Unit,
) {
    val density = LocalDensity.current
    androidx.compose.foundation.layout.Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { pos ->
                        val third = size.width / 3f
                        when {
                            pos.x < third -> onDoubleTapSeek(-10)
                            pos.x > 2 * third -> onDoubleTapSeek(+10)
                            else -> onTap()
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                var mode = GestureMode.None
                var accX = 0f
                var accY = 0f
                var startRightHalf = false
                detectDragGestures(
                    onDragStart = { offset ->
                        mode = GestureMode.None
                        accX = 0f
                        accY = 0f
                        startRightHalf = offset.x >= size.width / 2f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        accX += amount.x
                        accY += amount.y
                        if (mode == GestureMode.None) {
                            val slop = with(density) { 12.dp.toPx() }
                            if (abs(accX) > slop || abs(accY) > slop) {
                                mode = when {
                                    abs(accX) > abs(accY) -> GestureMode.Seek
                                    startRightHalf -> GestureMode.Volume
                                    else -> GestureMode.Brightness
                                }
                            }
                        }
                        when (mode) {
                            GestureMode.Seek ->
                                onSeekPreview((accX / size.width * FULL_SWIPE_SECONDS).toInt())
                            GestureMode.Volume ->
                                onVolumeDelta(-amount.y / size.height)
                            GestureMode.Brightness ->
                                onBrightnessDelta(-amount.y / size.height)
                            GestureMode.None -> {}
                        }
                    },
                    onDragEnd = {
                        if (mode == GestureMode.Seek) {
                            onSeekCommit((accX / size.width * FULL_SWIPE_SECONDS).toInt())
                        }
                        mode = GestureMode.None
                    },
                    onDragCancel = { mode = GestureMode.None },
                )
            }
    )
}
