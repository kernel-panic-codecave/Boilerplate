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
import net.kernelpanicsoft.tubularstorage.network.SItemResource
import net.kernelpanicsoft.tubularstorage.network.SResourceStack
import net.minecraft.core.Direction
import net.minecraft.world.item.DyeColor

/**
 * An item stack in flight through a pipe network.
 *
 * [color] is the consignment color set by whichever extractor/request initiated the trip (M2);
 * sorting pipes route on it, plain pipes ignore it. See `docs/design/m2-sorting-routing.md`.
 *
 * [targetFace] is the specific face of the *destination* block this delivery is meant for, when
 * the caller already knows exactly which one - a
 * [net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity] can carry up to six independent
 * hooks, and the direction [net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity.tick]'s
 * own final hop would otherwise compute reflects whichever neighboring pipe segment the item
 * happens to arrive from - pipe network topology, not which face the caller actually meant - so
 * two same-type hooks (two [net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookType]s,
 * say) on different faces of one block are otherwise indistinguishable at arrival time. `null` for
 * a delivery with no specific face in mind (a plain push via
 * [net.kernelpanicsoft.tubularstorage.pipe.network.PipeRouter.findRoute], or a target that was
 * never ambiguous - a single rack, a chest), which keeps today's "whichever face the topology
 * happens to land on" resolution exactly as it was.
 */
@Serializable
data class TravelingItem(
	val stack: SResourceStack<SItemResource>,
	val fromDirection: SDirection,
	var progress: Float = 0f,
	var path: List<SBlockPos> = emptyList(),
	val color: SDyeColor? = null,
	val targetFace: SDirection? = null,
)

typealias SDirection = @Serializable(with = DirectionSerializer::class) Direction
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
