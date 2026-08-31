package net.kernelpanicsoft.boilerplate.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.minecraft.core.Direction
import net.minecraft.world.item.DyeColor

/**
 * The shared enum-name wire/NBT serializers. kotlinx.serialization's default `encodeEnum`
 * falls through to an `Int` encoding knbt's `AbstractNbtEncoder` can't write, so any enum that
 * gets persisted or networked serializes as its enum name instead (the same gap
 * [net.kernelpanicsoft.boilerplate.pipe.entity.FilterModeSerializer] and
 * [net.kernelpanicsoft.boilerplate.crafting.PatternKindSerializer] already work around). The
 * `SDirection`/`SDyeColor` typealiases are the ready-wrapped forms applied directly at a field
 * site; the serializer objects are also imported directly where a raw `KSerializer` is wanted.
 */

/** [Direction] wire/NBT form - serialized as its enum name, see [DirectionSerializer]. */
typealias SDirection = @Serializable(with = DirectionSerializer::class) Direction

/** [DyeColor] wire/NBT form - serialized as its enum name, see [DyeColorSerializer]. */
typealias SDyeColor = @Serializable(with = DyeColorSerializer::class) DyeColor

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