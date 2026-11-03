package com.jagdeep.autoclicker.presentation.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.Alignment
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene

private const val TRANSITION_DURATION_MS = 300
private const val FADE_TRANSITION_DURATION_MS = 180
private const val SCALE_TRANSITION_DURATION_MS = 220
private const val SCALE_IN_INITIAL = 0.96f
private const val SCALE_OUT_TARGET = 1.04f
private const val EXPAND_TRANSITION_DURATION_MS = 260

internal fun AnimatedContentTransitionScope<Scene<NavKey>>.topLevelSlideTransition(
    topLevelRoutes: List<NavKey>,
): ContentTransform {
    val initialIndex = initialState.topLevelRouteIndex(topLevelRoutes)
    val targetIndex = targetState.topLevelRouteIndex(topLevelRoutes)
    return when {
        initialIndex == null && targetIndex != null -> slideBackTransition()
        initialIndex != null && targetIndex != null && targetIndex < initialIndex -> slideBackTransition()
        else -> slideForwardTransition()
    }
}

internal fun AnimatedContentTransitionScope<Scene<NavKey>>.topLevelFadeSlideTransition(
    topLevelRoutes: List<NavKey>,
): ContentTransform {
    val slideTransition = topLevelSlideTransition(topLevelRoutes)
    return ContentTransform(
        fadeIn(animationSpec = tween(FADE_TRANSITION_DURATION_MS)) + slideTransition.targetContentEnter,
        fadeOut(animationSpec = tween(FADE_TRANSITION_DURATION_MS)) + slideTransition.initialContentExit,
    )
}

internal fun AnimatedContentTransitionScope<Scene<NavKey>>.topLevelScaleSlideTransition(
    topLevelRoutes: List<NavKey>,
): ContentTransform {
    val slideTransition = topLevelSlideTransition(topLevelRoutes)
    return ContentTransform(
        scaleIn(
            initialScale = SCALE_IN_INITIAL,
            animationSpec = tween(SCALE_TRANSITION_DURATION_MS),
        ) + slideTransition.targetContentEnter,
        scaleOut(
            targetScale = SCALE_OUT_TARGET,
            animationSpec = tween(SCALE_TRANSITION_DURATION_MS),
        ) + slideTransition.initialContentExit,
    )
}

internal fun AnimatedContentTransitionScope<Scene<NavKey>>.topLevelVerticalSlideTransition(
    topLevelRoutes: List<NavKey>,
): ContentTransform {
    val initialIndex = initialState.topLevelRouteIndex(topLevelRoutes)
    val targetIndex = targetState.topLevelRouteIndex(topLevelRoutes)
    return if (initialIndex != null && targetIndex != null && targetIndex < initialIndex) {
        slideDownTransition()
    } else {
        slideUpTransition()
    }
}

internal fun AnimatedContentTransitionScope<Scene<NavKey>>.topLevelFadeVerticalSlideTransition(
    topLevelRoutes: List<NavKey>,
): ContentTransform {
    val slideTransition = topLevelVerticalSlideTransition(topLevelRoutes)
    return ContentTransform(
        fadeIn(animationSpec = tween(FADE_TRANSITION_DURATION_MS)) + slideTransition.targetContentEnter,
        fadeOut(animationSpec = tween(FADE_TRANSITION_DURATION_MS)) + slideTransition.initialContentExit,
    )
}

internal fun AnimatedContentTransitionScope<Scene<NavKey>>.topLevelFadeTransition(
    topLevelRoutes: List<NavKey>,
): ContentTransform {
    return fadeTransition()
}

internal fun AnimatedContentTransitionScope<Scene<NavKey>>.topLevelScaleTransition(
    topLevelRoutes: List<NavKey>,
): ContentTransform {
    return scaleTransition()
}

internal fun AnimatedContentTransitionScope<Scene<NavKey>>.topLevelFadeScaleTransition(
    topLevelRoutes: List<NavKey>,
): ContentTransform {
    return fadeScaleTransition()
}

internal fun AnimatedContentTransitionScope<Scene<NavKey>>.topLevelExpandShrinkTransition(
    topLevelRoutes: List<NavKey>,
): ContentTransform {
    return expandShrinkTransition()
}

internal fun AnimatedContentTransitionScope<Scene<NavKey>>.topLevelExpandShrinkVerticalTransition(
    topLevelRoutes: List<NavKey>,
): ContentTransform {
    return expandShrinkVerticalTransition()
}

internal fun AnimatedContentTransitionScope<Scene<NavKey>>.topLevelNoTransition(
    topLevelRoutes: List<NavKey>,
): ContentTransform {
    return noTransition()
}

internal fun AnimatedContentTransitionScope<*>.slideForwardTransition(): ContentTransform =
    ContentTransform(
        slideIntoContainer(
            AnimatedContentTransitionScope.SlideDirection.Left,
            animationSpec = tween(TRANSITION_DURATION_MS),
        ),
        slideOutOfContainer(
            AnimatedContentTransitionScope.SlideDirection.Left,
            animationSpec = tween(TRANSITION_DURATION_MS),
        ),
    )

internal fun AnimatedContentTransitionScope<*>.slideBackTransition(): ContentTransform =
    ContentTransform(
        slideIntoContainer(
            AnimatedContentTransitionScope.SlideDirection.Right,
            animationSpec = tween(TRANSITION_DURATION_MS),
        ),
        slideOutOfContainer(
            AnimatedContentTransitionScope.SlideDirection.Right,
            animationSpec = tween(TRANSITION_DURATION_MS),
        ),
    )

internal fun AnimatedContentTransitionScope<*>.slideUpTransition(): ContentTransform =
    ContentTransform(
        slideIntoContainer(
            AnimatedContentTransitionScope.SlideDirection.Up,
            animationSpec = tween(TRANSITION_DURATION_MS),
        ),
        slideOutOfContainer(
            AnimatedContentTransitionScope.SlideDirection.Up,
            animationSpec = tween(TRANSITION_DURATION_MS),
        ),
    )

internal fun AnimatedContentTransitionScope<*>.slideDownTransition(): ContentTransform =
    ContentTransform(
        slideIntoContainer(
            AnimatedContentTransitionScope.SlideDirection.Down,
            animationSpec = tween(TRANSITION_DURATION_MS),
        ),
        slideOutOfContainer(
            AnimatedContentTransitionScope.SlideDirection.Down,
            animationSpec = tween(TRANSITION_DURATION_MS),
        ),
    )

internal fun fadeTransition(): ContentTransform =
    ContentTransform(
        fadeIn(animationSpec = tween(FADE_TRANSITION_DURATION_MS)),
        fadeOut(animationSpec = tween(FADE_TRANSITION_DURATION_MS)),
    )

internal fun scaleTransition(): ContentTransform =
    ContentTransform(
        scaleIn(
            initialScale = SCALE_IN_INITIAL,
            animationSpec = tween(SCALE_TRANSITION_DURATION_MS),
        ),
        scaleOut(
            targetScale = SCALE_OUT_TARGET,
            animationSpec = tween(SCALE_TRANSITION_DURATION_MS),
        ),
    )

internal fun fadeScaleTransition(): ContentTransform =
    ContentTransform(
        fadeIn(animationSpec = tween(FADE_TRANSITION_DURATION_MS)) +
                scaleIn(
                    initialScale = SCALE_IN_INITIAL,
                    animationSpec = tween(SCALE_TRANSITION_DURATION_MS),
                ),
        fadeOut(animationSpec = tween(FADE_TRANSITION_DURATION_MS)) +
                scaleOut(
                    targetScale = SCALE_OUT_TARGET,
                    animationSpec = tween(SCALE_TRANSITION_DURATION_MS),
                ),
    )

internal fun expandShrinkTransition(): ContentTransform =
    ContentTransform(
        expandIn(
            expandFrom = Alignment.Center,
            animationSpec = tween(EXPAND_TRANSITION_DURATION_MS),
        ),
        shrinkOut(
            shrinkTowards = Alignment.Center,
            animationSpec = tween(EXPAND_TRANSITION_DURATION_MS),
        ),
    )

internal fun expandShrinkVerticalTransition(): ContentTransform =
    ContentTransform(
        expandVertically(
            expandFrom = Alignment.CenterVertically,
            animationSpec = tween(EXPAND_TRANSITION_DURATION_MS),
        ),
        shrinkVertically(
            shrinkTowards = Alignment.CenterVertically,
            animationSpec = tween(EXPAND_TRANSITION_DURATION_MS),
        ),
    )

internal fun noTransition(): ContentTransform =
    ContentTransform(
        EnterTransition.None,
        ExitTransition.None,
    )

private fun Scene<NavKey>.topLevelRouteIndex(topLevelRoutes: List<NavKey>): Int? =
    entries.lastOrNull()?.contentKey?.let { routeIndexOf(it, topLevelRoutes) }

/**
 * Position of an entry's route among [topLevelRoutes], or null for a non-top-level entry.
 * Navigation 3's default content key is `Pair("$key", "${key::class}")`, not the key's name, so
 * comparing `contentKey.toString()` with the route never matched and every change slid forward.
 */
internal fun routeIndexOf(contentKey: Any, topLevelRoutes: List<NavKey>): Int? {
    val name = ((contentKey as? Pair<*, *>)?.first ?: contentKey).toString()
    return topLevelRoutes.indexOfFirst { route -> route.toString() == name }.takeIf { it >= 0 }
}
