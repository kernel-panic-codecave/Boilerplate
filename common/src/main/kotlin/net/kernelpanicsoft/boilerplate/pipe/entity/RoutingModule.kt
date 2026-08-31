package net.kernelpanicsoft.boilerplate.pipe.entity

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.kernelpanicsoft.boilerplate.util.SDyeColor

enum class FilterMode { WHITELIST, BLACKLIST }

/**
 * A [KSerializer] for [FilterMode], encoded/decoded as its enum name rather than kotlinx.serialization's
 * default `encodeEnum`/ordinal-index path - knbt's `AbstractNbtEncoder` never overrides
 * `encodeEnum`, so the default `AbstractEncoder` implementation falls through to `encodeValue(Int)`,
 * which knbt's encoder doesn't support at all (throws `SerializationException: Non-serializable
 * class kotlin.Int...`). This bit for real: [RoutingModule.mode] silently worked for
 * [FilterMode.WHITELIST] only because it's the field's declared default, which
 * kotlinx.serialization skips encoding entirely - [FilterMode.BLACKLIST] crashed the moment
 * anything actually tried to persist it. Same fix [net.kernelpanicsoft.boilerplate.util.DirectionSerializer]/[net.kernelpanicsoft.boilerplate.util.DyeColorSerializer] use
 * for the same underlying knbt gap.
 */
object FilterModeSerializer : KSerializer<FilterMode> {
	override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FilterMode", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: FilterMode) = encoder.encodeString(value.name)
	override fun deserialize(decoder: Decoder): FilterMode = FilterMode.valueOf(decoder.decodeString())
}

/**
 * Sorting configuration applied to a [PipeBlockEntity] once a sorting module item is used on it.
 *
 * [priority] is normally `0`..10 (see `SortingPipeScreen`'s slider), but [DEFAULT_ROUTE_PRIORITY]
 * is a reserved sentinel below that range - Logistics Pipes' Default Route, a catch-all sink that
 * only wins in [net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter]'s candidate search
 * when nothing else on the network accepts an item, since it's ranked below even a plain hookless
 * destination's baseline (`0`). Only one sorting hook per network may hold it at a time -
 * [net.kernelpanicsoft.boilerplate.network.UpdateSortingRoutingPacket] clears it from any other
 * hook on the same network when a new one claims it.
 */
@Serializable
data class RoutingModule(
	@Serializable(with = FilterModeSerializer::class) val mode: FilterMode = FilterMode.WHITELIST,
	val priority: Int = 0,
	val color: SDyeColor? = null,
) {
	companion object {
		const val DEFAULT_ROUTE_PRIORITY = -1
	}
}
