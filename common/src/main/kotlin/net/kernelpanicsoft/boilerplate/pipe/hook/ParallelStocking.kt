package net.kernelpanicsoft.boilerplate.pipe.hook

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * How a [RequesterHookType] facing an interface spreads its row across the far subnet's own
 * destinations.
 *
 * Only meaningful there. Facing an ordinary inventory there is one destination and nothing to
 * spread; facing an interface there may be a dozen machines behind it, and "keep 64 coal supplied"
 * has two honest readings that suit different builds.
 */
@Serializable(with = ParallelStockingSerializer::class)
enum class ParallelStocking {
	/**
	 * Every destination is kept at the row's own amount. Ten furnaces behind the interface each hold
	 * 64 coal, and the row's number is read as "this much *at each*".
	 *
	 * The default, because it is what a row of machines usually wants: each one runs from its own
	 * buffer without waiting on a share-out.
	 */
	EACH,

	/**
	 * The row's amount is the total across the far subnet, divided between whatever accepts it. Ten
	 * furnaces share 64 coal between them.
	 *
	 * For a resource that is scarce or expensive to make, where filling every machine to the brim
	 * would commit far more of it than the process actually needs in flight.
	 */
	SPLIT,
}

/**
 * By name, not by ordinal - the same shape [net.kernelpanicsoft.boilerplate.pipe.entity.FilterModeSerializer]
 * takes and for the same two reasons: NBT has no notion of an enum and knbt refuses the `Int` the
 * default encoder reaches for, and a saved ordinal would silently change meaning the moment a
 * constant is inserted above it.
 */
object ParallelStockingSerializer : KSerializer<ParallelStocking> {
	override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ParallelStocking", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: ParallelStocking) = encoder.encodeString(value.name)
	override fun deserialize(decoder: Decoder): ParallelStocking = ParallelStocking.valueOf(decoder.decodeString())
}
