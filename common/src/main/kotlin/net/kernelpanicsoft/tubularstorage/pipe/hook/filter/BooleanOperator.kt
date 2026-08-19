package net.kernelpanicsoft.tubularstorage.pipe.hook.filter

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * How a [CombinedConditionType] card aggregates its own non-blank
 * [FilterCardState.children][net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterCardState.children] -
 * unlike [FilterConditionType] itself, this is a closed, genuinely fixed set (De Morgan's laws
 * plus each child's own [net.kernelpanicsoft.tubularstorage.pipe.entity.FilterMode] already cover
 * NOT), so a plain enum - same choice [net.kernelpanicsoft.tubularstorage.pipe.entity.FilterMode]
 * itself makes - rather than another registry.
 */
enum class BooleanOperator { AND, OR }

/** A [KSerializer] for [BooleanOperator], encoded/decoded as its enum name - same knbt-enum workaround as [net.kernelpanicsoft.tubularstorage.pipe.entity.FilterModeSerializer]. */
object BooleanOperatorSerializer : KSerializer<BooleanOperator> {
	override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("BooleanOperator", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: BooleanOperator) = encoder.encodeString(value.name)
	override fun deserialize(decoder: Decoder): BooleanOperator = BooleanOperator.valueOf(decoder.decodeString())
}
