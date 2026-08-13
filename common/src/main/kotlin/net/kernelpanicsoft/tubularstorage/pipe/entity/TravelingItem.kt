package net.kernelpanicsoft.tubularstorage.pipe.entity

import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.archie.serialization.serializers.SItemStack
import net.minecraft.core.Direction
import net.minecraft.world.item.DyeColor

/**
 * An item stack in flight through a pipe network.
 *
 * [color] is the consignment color set by whichever extractor/request initiated the trip (M2);
 * sorting pipes route on it, plain pipes ignore it. See `docs/design/m2-sorting-routing.md`.
 */
@Serializable
data class TravelingItem(
	val stack: SItemStack,
	val fromDirection: SDirection,
	var progress: Float = 0f,
	var path: List<SBlockPos> = emptyList(),
	val color: SDyeColor? = null,
)

typealias SDirection = @Contextual Direction
typealias SDyeColor = @Contextual DyeColor

/** A [KSerializer] for [Direction], encoded/decoded as its enum name. */
object DirectionSerializer : KSerializer<Direction> {
	override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Direction", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: Direction) = encoder.encodeString(value.name)
	override fun deserialize(decoder: Decoder): Direction = Direction.valueOf(decoder.decodeString())
}

/** A [KSerializer] for [DyeColor], encoded/decoded as its enum name. */
object DyeColorSerializer : KSerializer<DyeColor> {
	override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("DyeColor", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: DyeColor) = encoder.encodeString(value.name)
	override fun deserialize(decoder: Decoder): DyeColor = DyeColor.valueOf(decoder.decodeString())
}
