package net.kernelpanicsoft.tubularstorage.pipe.entity

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

/** An item stack in flight through a pipe network. */
@Serializable
data class TravelingItem(
	val stack: SItemStack,
	val fromDirection: @Serializable(with = DirectionSerializer::class) Direction,
	var progress: Float = 0f,
	var path: List<SBlockPos> = emptyList(),
)

/** A [KSerializer] for [Direction], encoded/decoded as its enum name. */
object DirectionSerializer : KSerializer<Direction> {
	override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Direction", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: Direction) = encoder.encodeString(value.name)
	override fun deserialize(decoder: Decoder): Direction = Direction.valueOf(decoder.decodeString())
}
