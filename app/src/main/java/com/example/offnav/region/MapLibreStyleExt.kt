package com.example.offnav.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.Layer

/**
 * Inserts [layer] at the very bottom of the stack, except that it stays *above* a
 * BackgroundLayer if the style has one — otherwise the background simply paints over it.
 */
fun Style.addLayerAtBase(layer: Layer) {
    val first = layers.firstOrNull()
    if (first is BackgroundLayer) addLayerAbove(layer, first.id) else addLayerAt(layer, 0)
}