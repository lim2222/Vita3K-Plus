package org.vita3k.emulator.overlay

import java.util.Collections
import java.util.LinkedHashMap

data class OverlayLayout(
    val portraitPositions: Map<Int, OverlayPosition> = emptyMap(),
    val landscapePositions: Map<Int, OverlayPosition> = emptyMap()
) {
    companion object {
        // Java-friendly factory function
        @JvmStatic
        fun createFromJavaMaps(portraitPositions: java.util.Map<Int, OverlayPosition>, landscapePositions: java.util.Map<Int, OverlayPosition>): OverlayLayout {
            return OverlayLayout(
                portraitPositions = javaUtilMapToKotlin(portraitPositions),
                landscapePositions = javaUtilMapToKotlin(landscapePositions)
            )
        }
    }

    fun positionFor(buttonType: Int, isPortrait: Boolean): OverlayPosition? =
        if (isPortrait) portraitPositions[buttonType] else landscapePositions[buttonType]

    fun withPosition(buttonType: Int, normalizedX: Float, normalizedY: Float, scale: Float = 1.0f, isPortrait: Boolean): OverlayLayout =
        if (isPortrait) {
            copy(
                portraitPositions = portraitPositions + (
                    buttonType to OverlayPosition(
                        normalizedX = normalizedX.coerceNormalizedCoordinate(),
                        normalizedY = normalizedY.coerceNormalizedCoordinate(),
                        scale = scale.coerceIn(0.2f, 20.0f)
                    )
                )
            )
        } else {
            copy(
                landscapePositions = landscapePositions + (
                    buttonType to OverlayPosition(
                        normalizedX = normalizedX.coerceNormalizedCoordinate(),
                        normalizedY = normalizedY.coerceNormalizedCoordinate(),
                        scale = scale.coerceIn(0.2f, 20.0f)
                    )
                )
            )
        }

    fun normalized(): OverlayLayout =
        copy(
            portraitPositions = portraitPositions.mapValues { (_, position) ->
                OverlayPosition(
                    normalizedX = position.normalizedX.coerceNormalizedCoordinate(),
                    normalizedY = position.normalizedY.coerceNormalizedCoordinate(),
                    scale = position.scale.coerceIn(0.2f, 20.0f)
                )
            },
            landscapePositions = landscapePositions.mapValues { (_, position) ->
                OverlayPosition(
                    normalizedX = position.normalizedX.coerceNormalizedCoordinate(),
                    normalizedY = position.normalizedY.coerceNormalizedCoordinate(),
                    scale = position.scale.coerceIn(0.2f, 20.0f)
                )
            }
        )

    // Java-friendly getters - return unmodifiable Java maps
    @JvmName("getPortraitPositionsJava")
    fun getPortraitPositions(): java.util.Map<Int, OverlayPosition> {
        val map: LinkedHashMap<Int, OverlayPosition> = LinkedHashMap()
        portraitPositions.forEach { (k, v) -> map.put(k, v) }
        return Collections.unmodifiableMap(map) as java.util.Map<Int, OverlayPosition>
    }
    
    @JvmName("getLandscapePositionsJava")
    fun getLandscapePositions(): java.util.Map<Int, OverlayPosition> {
        val map: LinkedHashMap<Int, OverlayPosition> = LinkedHashMap()
        landscapePositions.forEach { (k, v) -> map.put(k, v) }
        return Collections.unmodifiableMap(map) as java.util.Map<Int, OverlayPosition>
    }
}

// Helper function to convert Java Map to Kotlin Map
private fun <K, V> javaUtilMapToKotlin(javaMap: java.util.Map<K, V>): Map<K, V> {
    val kotlinMap = mutableMapOf<K, V>()
    val iterator = javaMap.entrySet().iterator()
    while (iterator.hasNext()) {
        val entry = iterator.next()
        kotlinMap[entry.key] = entry.value
    }
    return kotlinMap
}

data class OverlayState(
    val config: OverlayConfig,
    val layout: OverlayLayout
)

internal fun Float.coerceNormalizedCoordinate(): Float {
    if (!isFinite()) {
        return 0f
    }
    return coerceIn(0f, 1f)
}