package net.kernelpanicsoft.boilerplate.pipe.hook

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * How an [ExtractionHookType] hook chooses between destinations that would all accept what it is
 * pulling.
 *
 * Only ever a question when more than one would take it. With a single willing destination both
 * modes send everything to the same place.
 *
 * Neither mode replaces the router's own ranking - priority first, then distance
 * ([net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter]). What they choose is whether a
 * destination this hook has already served stays in the running.
 */
@Serializable(with = ExtractionDistributionSerializer::class)
enum class ExtractionDistribution {
	/**
	 * Whichever destination the router ranks highest, every time.
	 *
	 * The default, and what a pipe does elsewhere in the genre - a hook dropped on a chest with no
	 * further configuration behaves the way someone coming from another mod expects it to. It is
	 * also what a build wants whenever the destinations are not peers: a machine fed until it is
	 * full with an overflow chest behind it. The top-ranked destination keeps winning for as long as
	 * it keeps accepting, and the next one down sees nothing until it stops.
	 */
	NEAREST_FIRST,

	/**
	 * Each destination in turn, skipping whatever this hook has already served this cycle and
	 * starting the round again once nothing new will take it.
	 *
	 * For a row of peers - a bank of furnaces - where [NEAREST_FIRST] would pin everything on
	 * whichever one the router happens to rank first. The router's answer is cached per network,
	 * resource and colour, so without a cursor moving between them the same destination wins every
	 * pull and one machine of several receives everything.
	 *
	 * The cursor narrows the field; it does not flatten it. Each pull still asks the router for the
	 * best of whatever has *not* been served yet, so priority decides the order a round is served in
	 * rather than being ignored - the highest-ranked destination first, then the next, until the
	 * round starts over. Both modes rank the same way; what differs is whether a destination already
	 * served this round is still a candidate.
	 */
	ROUND_ROBIN,
}

/**
 * By name, not by ordinal - the same shape [ParallelStockingSerializer] takes and for the same two
 * reasons: NBT has no notion of an enum and knbt refuses the `Int` the default encoder reaches for,
 * and a saved ordinal would silently change meaning the moment a constant is inserted above it.
 */
object ExtractionDistributionSerializer : KSerializer<ExtractionDistribution> {
	override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ExtractionDistribution", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: ExtractionDistribution) = encoder.encodeString(value.name)
	override fun deserialize(decoder: Decoder): ExtractionDistribution = ExtractionDistribution.valueOf(decoder.decodeString())
}
