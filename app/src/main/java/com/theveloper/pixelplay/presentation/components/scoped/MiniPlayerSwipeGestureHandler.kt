package com.theveloper.pixelplay.presentation.components.scoped

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

private enum class MiniPlayerSwipeDragPhase { IDLE, TENSION, SNAPPING, FREE_DRAG }

/**
 * Keeps mini-player track-swipe behavior isolated from the sheet host.
 */
internal class MiniPlayerSwipeGestureHandler(
    private val scope: CoroutineScope,
    private val density: Density,
    private val hapticFeedback: HapticFeedback,
    private val offsetAnimatable: Animatable<Float, AnimationVector1D>,
    private val screenWidthPx: Float,
    private val onSwipeToNext: () -> Unit,
    private val onSwipeToPrevious: () -> Unit
) {
    private var dragPhase: MiniPlayerSwipeDragPhase = MiniPlayerSwipeDragPhase.IDLE
    private var accumulatedDragX: Float = 0f
    private var offsetJob: Job? = null

    fun onDragStart() {
        dragPhase = MiniPlayerSwipeDragPhase.TENSION
        accumulatedDragX = 0f
        offsetJob?.cancel()
        offsetJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            offsetAnimatable.stop()
        }
    }

    fun onHorizontalDrag(dragAmount: Float) {
        accumulatedDragX += dragAmount

        when (dragPhase) {
            MiniPlayerSwipeDragPhase.TENSION -> {
                val snapThresholdPx = 100f * density.density
                if (abs(accumulatedDragX) < snapThresholdPx) {
                    val maxTensionOffsetPx = 30f * density.density
                    val dragFraction = (abs(accumulatedDragX) / snapThresholdPx).coerceIn(0f, 1f)
                    val tensionOffset = lerp(0f, maxTensionOffsetPx, dragFraction)
                    offsetJob?.cancel()
                    offsetJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        offsetAnimatable.snapTo(tensionOffset * accumulatedDragX.sign)
                    }
                } else {
                    dragPhase = MiniPlayerSwipeDragPhase.SNAPPING
                }
            }

            MiniPlayerSwipeDragPhase.SNAPPING -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                offsetJob?.cancel()
                offsetJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    offsetAnimatable.animateTo(
                        targetValue = accumulatedDragX,
                        animationSpec = spring(
                            dampingRatio = 0.8f,
                            stiffness = Spring.StiffnessLow
                        )
                    )
                }
                dragPhase = MiniPlayerSwipeDragPhase.FREE_DRAG
            }

            MiniPlayerSwipeDragPhase.FREE_DRAG -> {
                offsetJob?.cancel()
                offsetJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    offsetAnimatable.animateTo(
                        targetValue = accumulatedDragX,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessHigh
                        )
                    )
                }
            }

            MiniPlayerSwipeDragPhase.IDLE -> Unit
        }
    }

    fun onDragEnd() {
        dragPhase = MiniPlayerSwipeDragPhase.IDLE
        offsetJob?.cancel()
        val trackSwipeThreshold = screenWidthPx * 0.4f
        if (abs(accumulatedDragX) > trackSwipeThreshold) {
            val swipedLeft = accumulatedDragX < 0
            val targetOffset = if (swipedLeft) -screenWidthPx else screenWidthPx
            offsetJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                offsetAnimatable.animateTo(
                    targetValue = targetOffset,
                    animationSpec = tween(
                        durationMillis = 200,
                        easing = FastOutSlowInEasing
                    )
                )
                if (swipedLeft) onSwipeToNext() else onSwipeToPrevious()
                offsetAnimatable.snapTo(0f)
            }
        } else {
            offsetJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                offsetAnimatable.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            }
        }
    }
}

@Composable
internal fun rememberMiniPlayerSwipeGestureHandler(
    scope: CoroutineScope,
    density: Density,
    hapticFeedback: HapticFeedback,
    offsetAnimatable: Animatable<Float, AnimationVector1D>,
    screenWidthPx: Float,
    onSwipeToNext: () -> Unit,
    onSwipeToPrevious: () -> Unit
): MiniPlayerSwipeGestureHandler {
    val onSwipeToNextState = rememberUpdatedState(onSwipeToNext)
    val onSwipeToPreviousState = rememberUpdatedState(onSwipeToPrevious)
    return remember(scope, density, hapticFeedback, offsetAnimatable, screenWidthPx) {
        MiniPlayerSwipeGestureHandler(
            scope = scope,
            density = density,
            hapticFeedback = hapticFeedback,
            offsetAnimatable = offsetAnimatable,
            screenWidthPx = screenWidthPx,
            onSwipeToNext = { onSwipeToNextState.value() },
            onSwipeToPrevious = { onSwipeToPreviousState.value() }
        )
    }
}

internal fun Modifier.miniPlayerSwipeHorizontalGesture(
    enabled: Boolean,
    handler: MiniPlayerSwipeGestureHandler
): Modifier {
    if (!enabled) return this
    return this.pointerInput(enabled, handler) {
        detectHorizontalDragGestures(
            onDragStart = { handler.onDragStart() },
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                handler.onHorizontalDrag(dragAmount)
            },
            onDragEnd = { handler.onDragEnd() }
        )
    }
}
