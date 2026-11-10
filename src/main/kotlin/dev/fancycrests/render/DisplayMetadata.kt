package dev.fancycrests.render

import dev.fancycrests.api.RenderOffset
import dev.fancycrests.api.RenderSettings
import dev.fancycrests.api.DisplaySnapshot
import dev.fancycrests.mixin.BillboardConstraintsAccessor
import dev.fancycrests.mixin.DisplayAccessor
import dev.fancycrests.mixin.TextDisplayAccessor
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.entity.Display
import org.joml.Vector3f

/**
 * Label layers of one target: the first line is rendered as two stacked
 * same-text entities (a fully opaque foreground and a see-through background
 * with a translucent plate); the second line is a single entity and only
 * exists while non-empty.
 */
enum class LabelLayer {
    FIRST_FOREGROUND,
    FIRST_BACKGROUND,
    SECOND_LINE,
}

/**
 * Fixed visual behaviour of the label layers, normal vs sneaking.
 *
 * All opacity constants are documented P0-tunable values; the sneak state of
 * the first-line foreground is expressed by clearing its TEXT (an empty text
 * component draws nothing), never by relying on unverified zero opacity.
 */
internal object LabelStyles {

    /** Vanilla default plate: 25% black (0x40000000). */
    const val PLATE_NORMAL_ARGB: Int = 0x40000000.toInt()

    /** Dimmed plate while sneaking: ~15% black. */
    const val PLATE_SNEAK_ARGB: Int = 0x26000000.toInt()

    /** Fully transparent plate (foreground layer never draws a plate). */
    const val PLATE_NONE_ARGB: Int = 0x00000000

    /** Vanilla default text opacity byte (-1 = fully opaque default). */
    const val TEXT_OPAQUE: Byte = -1

    /** Background-layer text at ~35% alpha in normal state. */
    const val TEXT_BG_NORMAL: Byte = 0x59

    /** Background-layer text at ~20% alpha while sneaking. */
    const val TEXT_BG_SNEAK: Byte = 0x33

    /** Second-line text dimmed to ~30% alpha while sneaking. */
    const val TEXT_LINE2_SNEAK: Byte = 0x4D

    const val FLAG_SEE_THROUGH: Byte = Display.TextDisplay.FLAG_SEE_THROUGH
    const val FLAG_NONE: Byte = 0

    /** Text shown on a layer for the current snapshot/sneak state. */
    fun textOf(layer: LabelLayer, snapshot: DisplaySnapshot, sneaking: Boolean): Component = when (layer) {
        // Sneak: foreground stops drawing by clearing its text.
        LabelLayer.FIRST_FOREGROUND -> if (sneaking) Component.empty() else snapshot.firstLine
        LabelLayer.FIRST_BACKGROUND -> snapshot.firstLine
        LabelLayer.SECOND_LINE -> snapshot.secondLine ?: Component.empty()
    }

    /** Plate colour of a layer for the current sneak state. */
    fun plateOf(layer: LabelLayer, sneaking: Boolean): Int = when (layer) {
        LabelLayer.FIRST_FOREGROUND -> PLATE_NONE_ARGB
        LabelLayer.FIRST_BACKGROUND -> if (sneaking) PLATE_SNEAK_ARGB else PLATE_NORMAL_ARGB
        LabelLayer.SECOND_LINE -> if (sneaking) PLATE_SNEAK_ARGB else PLATE_NORMAL_ARGB
    }

    /** Text opacity byte of a layer for the current sneak state. */
    fun textOpacityOf(layer: LabelLayer, sneaking: Boolean): Byte = when (layer) {
        LabelLayer.FIRST_FOREGROUND -> TEXT_OPAQUE
        LabelLayer.FIRST_BACKGROUND -> if (sneaking) TEXT_BG_SNEAK else TEXT_BG_NORMAL
        LabelLayer.SECOND_LINE -> if (sneaking) TEXT_LINE2_SNEAK else TEXT_OPAQUE
    }

    /** Style flags of a layer; see-through is on only for the background layer while not sneaking. */
    fun flagsOf(layer: LabelLayer, sneaking: Boolean): Byte = when (layer) {
        LabelLayer.FIRST_FOREGROUND -> FLAG_NONE
        LabelLayer.FIRST_BACKGROUND -> if (sneaking) FLAG_NONE else FLAG_SEE_THROUGH
        LabelLayer.SECOND_LINE -> FLAG_NONE
    }

    /** Configured offset of a layer (both first-line layers share one offset). */
    fun offsetOf(layer: LabelLayer, settings: RenderSettings): RenderOffset = when (layer) {
        LabelLayer.FIRST_FOREGROUND, LabelLayer.FIRST_BACKGROUND -> settings.firstLineOffset
        LabelLayer.SECOND_LINE -> settings.secondLineOffset
    }
}

/**
 * Builds the metadata values for virtual text_display entities from the
 * private accessor mixins. Every value list produced here is a fresh list of
 * freshly created DataValues; shared snapshot components are reused
 * read-only.
 */
internal object DisplayMetadata {

    /** Wire byte of billboard=center, read through the invoker (not hardcoded). */
    val BILLBOARD_CENTER: Byte =
        (Display.BillboardConstraints.CENTER as BillboardConstraintsAccessor).fancycrestsGetId()

    fun translationValue(offset: RenderOffset): SynchedEntityData.DataValue<*> =
        SynchedEntityData.DataValue.create(
            DisplayAccessor.fancycrestsTranslationId(),
            Vector3f(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat()),
        )

    fun billboardValue(): SynchedEntityData.DataValue<*> =
        SynchedEntityData.DataValue.create(DisplayAccessor.fancycrestsBillboardId(), BILLBOARD_CENTER)

    fun viewRangeValue(blocks: Double): SynchedEntityData.DataValue<*> =
        SynchedEntityData.DataValue.create(
            DisplayAccessor.fancycrestsViewRangeId(),
            ViewRanges.blocksToViewRange(blocks),
        )

    fun textValue(text: Component): SynchedEntityData.DataValue<*> =
        SynchedEntityData.DataValue.create(TextDisplayAccessor.fancycrestsTextId(), text)

    fun plateValue(argb: Int): SynchedEntityData.DataValue<*> =
        SynchedEntityData.DataValue.create(TextDisplayAccessor.fancycrestsBackgroundColorId(), argb)

    fun textOpacityValue(opacity: Byte): SynchedEntityData.DataValue<*> =
        SynchedEntityData.DataValue.create(TextDisplayAccessor.fancycrestsTextOpacityId(), opacity)

    fun flagsValue(flags: Byte): SynchedEntityData.DataValue<*> =
        SynchedEntityData.DataValue.create(TextDisplayAccessor.fancycrestsStyleFlagsId(), flags)

    /** Content values that change with snapshot text or sneak state. */
    fun contentValues(layer: LabelLayer, snapshot: DisplaySnapshot, sneaking: Boolean): List<SynchedEntityData.DataValue<*>> =
        listOf(
            textValue(LabelStyles.textOf(layer, snapshot, sneaking)),
            plateValue(LabelStyles.plateOf(layer, sneaking)),
            textOpacityValue(LabelStyles.textOpacityOf(layer, sneaking)),
            flagsValue(LabelStyles.flagsOf(layer, sneaking)),
        )

    /** Full initial metadata: content + billboard + translation + view range. */
    fun fullValues(
        layer: LabelLayer,
        snapshot: DisplaySnapshot,
        sneaking: Boolean,
        settings: RenderSettings,
    ): List<SynchedEntityData.DataValue<*>> =
        contentValues(layer, snapshot, sneaking) + listOf(
            billboardValue(),
            translationValue(LabelStyles.offsetOf(layer, settings)),
            viewRangeValue(settings.renderDistance.blocks),
        )

    /** Placement-only updates (settings hot reload). */
    fun placementValues(layer: LabelLayer, settings: RenderSettings): List<SynchedEntityData.DataValue<*>> =
        listOf(
            translationValue(LabelStyles.offsetOf(layer, settings)),
            viewRangeValue(settings.renderDistance.blocks),
        )
}
