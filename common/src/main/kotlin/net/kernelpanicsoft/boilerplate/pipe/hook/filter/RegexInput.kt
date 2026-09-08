package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry

@Serializable(with = RegexInputMatcherSerializer::class)
enum class RegexInputMatcher
{
	ID {
		override fun match(context: FilterContext): List<String>
		{
			return ResourceKindRegistry.forResource(context.resource)?.registryId(context.resource)?.toString()?.let { listOf(it) } ?: listOf()
		}
	},
	NAME {
		override fun match(context: FilterContext): List<String>
		{
			return ResourceKindRegistry.forResource(context.resource)?.displayName(context.resource)?.string?.let { listOf(it) } ?: listOf()
		}
	},
	TAG {
		override fun match(context: FilterContext): List<String>
		{
			return ResourceKindRegistry.forResource(context.resource)?.tagsOf(context.resource).orEmpty().map { it.location.toString() }
		}
	};
	abstract fun match(context: FilterContext): List<String>
}

/** A [KSerializer] for [RegexInputMatcher], encoded/decoded as its enum name - same knbt-enum workaround as [net.kernelpanicsoft.boilerplate.pipe.entity.FilterModeSerializer]. */
object RegexInputMatcherSerializer : KSerializer<RegexInputMatcher> {
	override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("RegexInputMatcher", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: RegexInputMatcher) = encoder.encodeString(value.name)
	override fun deserialize(decoder: Decoder): RegexInputMatcher = RegexInputMatcher.valueOf(decoder.decodeString())
}