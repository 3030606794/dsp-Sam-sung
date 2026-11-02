package me.timschneeberger.rootlessjamesdsp.creative

import me.timschneeberger.rootlessjamesdsp.effectvisuals.EffectVisualPair

/** Shared layout, independent professional diagrams and effect illustrations. */
internal object CreativeEffectsLinkedPlots {
    fun pair(kind: CreativeEffectsKind, values: Map<String, *>, focusKey: String?): EffectVisualPair =
        CreativeEffectsVisuals.pair(kind, values, focusKey)
}
