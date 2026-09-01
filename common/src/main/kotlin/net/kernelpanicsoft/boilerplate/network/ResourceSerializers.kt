package net.kernelpanicsoft.boilerplate.network

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlinx.serialization.modules.polymorphic
import net.benwoodworth.knbt.NbtTag
import net.kernelpanicsoft.archie.serialization.CodecSerializer
import net.kernelpanicsoft.archie.serialization.SerializationManager
import net.kernelpanicsoft.archie.serialization.defer
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import kotlin.reflect.KClass

/**
 * The serializable wire/NBT forms of the common-storage resource types - every packet payload
 * and persisted field that crosses the network or knbt declares its resources as
 * `SItemResource`/`SResourceStack<*>` instead of the raw types, so common-storage's own CODECs
 * do the actual work through [CodecSerializer]. Split out of
 * [BoilerplateNetworkChannel] so the channel file is only the channel.
 */

/** [ItemResource] with [ItemResourceSerializer] applied. */
typealias SItemResource = @Serializable(with = ItemResourceSerializer::class) ItemResource

/** [FluidResource] with [FluidResourceSerializer] applied. */
typealias SFluidResource = @Serializable(with = FluidResourceSerializer::class) FluidResource

/** [ResourceComponent] with [ResourceComponentSerializer] applied. */
typealias SResourceComponent = @Serializable(with = ResourceComponentSerializer::class) ResourceComponent

/** [ResourceStack] of any [ResourceComponent] with [ResourceStackSerializer] applied. */
typealias SResourceStack<T> = @Serializable(with = ResourceStackSerializer::class) ResourceStack<T>

object ItemResourceSerializer : CodecSerializer<ItemResource>(ItemResource.CODEC)
object FluidResourceSerializer : CodecSerializer<FluidResource>(FluidResource.CODEC)

object ResourceComponentSerializer : KSerializer<ResourceComponent> {
	override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ResourceComponent") {
		element<String>("kind")
		element("value", defer { ItemResourceSerializer.descriptor })
	}

	override fun serialize(encoder: Encoder, value: ResourceComponent) {
		val composite = encoder.beginStructure(descriptor)
		val kind = ResourceKindRegistry.forResource(value)
			?: throw kotlinx.serialization.SerializationException("Unknown resource component: $value")
		composite.encodeStringElement(descriptor, 0, kind.kindTag)
		composite.encodeSerializableElement(descriptor, 1, kind.serializer, value)
		composite.endStructure(descriptor)
	}

	override fun deserialize(decoder: Decoder): ResourceComponent {
		val composite = decoder.beginStructure(descriptor)
		var kindTag: String? = null
		var dataTag: NbtTag? = null
		var value: ResourceComponent? = null

		while (true) {
			when (val index = composite.decodeElementIndex(descriptor)) {
				0 -> {
					kindTag = composite.decodeStringElement(descriptor, 0)
					if (dataTag != null) {
						val kind = ResourceKindRegistry.byTag(kindTag)
							?: throw kotlinx.serialization.SerializationException("Unknown resource kind tag: $kindTag")
						value = SerializationManager.nbt.decodeFromNbtTag(kind.serializer, dataTag)
					}
				}
				1 -> {
					if (kindTag == null) {
						dataTag = composite.decodeSerializableElement(descriptor, 1, NbtTag.serializer())
					}
					else {
						val tag = kindTag
						val kind = ResourceKindRegistry.byTag(tag)
							?: throw kotlinx.serialization.SerializationException("Unknown resource kind tag: $tag")
						value = composite.decodeSerializableElement(descriptor, 1, kind.serializer)
					}
				}
				CompositeDecoder.DECODE_DONE -> break
				else -> throw kotlinx.serialization.SerializationException("Unknown index $index")
			}
		}
		composite.endStructure(descriptor)
		return value ?: throw kotlinx.serialization.SerializationException("Missing resource value")
	}
}

/**
 * Serializes a [ResourceStack] of any registered carrier resource - the wire/NBT form of
 * `SResourceStack<*>` as used by [net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem.stack]
 * and the packet/cache payloads.
 */
object ResourceStackSerializer : KSerializer<ResourceStack<*>> {
	@Serializable
	private data class Surrogate(
		val resource: SResourceComponent,
		val amount: Long
	)

	override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

	override fun serialize(encoder: Encoder, value: ResourceStack<*>) {
		encoder.encodeSerializableValue(Surrogate.serializer(), Surrogate(value.resource as ResourceComponent, value.amount))
	}

	override fun deserialize(decoder: Decoder): ResourceStack<*> {
		val surrogate = decoder.decodeSerializableValue(Surrogate.serializer())
		return ResourceStack(surrogate.resource, surrogate.amount)
	}
}

/** The generated [ResourceStack] wire shape - [resource] self-identifies through the module's polymorphic dispatch. */


/**
 * Registers every [ResourceKindRegistry] kind as a polymorphic subclass of [ResourceComponent] so
 * [ResourceComponentSerializer] can dispatch on the wire discriminator (the [ResourceKind.kindTag]).
 * Each kind's body serializer runs under a descriptor named by its [kindTag], which is what the
 * polymorphic encoder writes as the discriminator and the decoder looks up to find it back.
 */
fun SerializersModuleBuilder.registerResourceKinds() {
	polymorphic(ResourceComponent::class) {
		for ((_, kind) in ResourceKindRegistry) {
			@Suppress("UNCHECKED_CAST")
			subclass(kind.get().resourceClass.kotlin as KClass<ResourceComponent>, RegisteredResource(kind.get()))
		}
	}
}

/**
 * A registered [ResourceKind]'s wire form - the kind's [ResourceKind.serializer] under a descriptor
 * named by its [ResourceKind.kindTag], so the polymorphic encoder writes the tag as the discriminator.
 */
private class RegisteredResource(kind: ResourceKind) : KSerializer<ResourceComponent> {
	private val delegate: KSerializer<ResourceComponent> = kind.serializer
	override val descriptor: SerialDescriptor = SerialDescriptor(kind.kindTag, delegate.descriptor)
	override fun serialize(encoder: Encoder, value: ResourceComponent) = delegate.serialize(encoder, value)
	override fun deserialize(decoder: Decoder): ResourceComponent = delegate.deserialize(decoder)
}
