package net.kernelpanicsoft.boilerplate.network

import com.mojang.serialization.Codec
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.CodecSerializer

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

/** [ResourceStack] of any [ResourceComponent] with [ResourceStackSerializer] applied. */
typealias SResourceStack<T> = @Serializable(with = ResourceStackSerializer::class) ResourceStack<T>

object ItemResourceSerializer : CodecSerializer<ItemResource>(ItemResource.CODEC)
object FluidResourceSerializer : CodecSerializer<FluidResource>(FluidResource.CODEC)

class ResourceStackSerializer<T : ResourceComponent>(resource: KSerializer<T>) : CodecSerializer<ResourceStack<T>>((when (resource)
{
	ItemResourceSerializer -> ResourceStack.ITEM_CODEC
	FluidResourceSerializer -> ResourceStack.FLUID_CODEC
	else -> error("Unexpected resource type: $resource")
}) as Codec<ResourceStack<T>>)