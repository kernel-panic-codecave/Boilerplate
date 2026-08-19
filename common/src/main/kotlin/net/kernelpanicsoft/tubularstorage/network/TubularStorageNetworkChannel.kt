package net.kernelpanicsoft.tubularstorage.network

import com.mojang.serialization.Codec
import earth.terrarium.common_storage_lib.resources.Resource
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.UseSerializers
import net.kernelpanicsoft.archie.networking.NetworkChannel
import net.kernelpanicsoft.archie.serialization.CodecSerializer
import net.kernelpanicsoft.archie.serialization.kSerializer
import net.kernelpanicsoft.archie.util.onClient
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.tubularstorage.TubularStorage
import kotlin.collections.first

typealias SItemResource = @Serializable(with = ItemResourceSerializer::class) ItemResource
typealias SFluidResource = @Serializable(with = FluidResourceSerializer::class) FluidResource
typealias SResourceStack<T> = @Serializable(with = ResourceStackSerializer::class) ResourceStack<T>

object ItemResourceSerializer : CodecSerializer<ItemResource>(ItemResource.CODEC)
object FluidResourceSerializer : CodecSerializer<FluidResource>(FluidResource.CODEC)

class ResourceStackSerializer<T : Resource>(resource: KSerializer<T>) : CodecSerializer<ResourceStack<T>>((when (resource)
{
	ItemResourceSerializer -> ResourceStack.ITEM_CODEC
	FluidResourceSerializer -> ResourceStack.FLUID_CODEC
	else -> error("Unexpected resource type: $resource")
}) as Codec<ResourceStack<T>>)

/** Tubular Storage's own network channel, separate from Archie's internal one. */
object TubularStorageNetworkChannel : NetworkChannel(TubularStorage.MOD % "main") {
	fun init() {
		onClient {
			clientbound(PipeContentsSyncPacket::class) { packet, _ -> packet.handleOnClient() }
			clientbound(GantrySyncPacket::class) { packet, _ -> packet.handleOnClient() }
			clientbound(TerminalSearchResultsPacket::class) { packet, _ -> packet.handleOnClient() }
			clientbound(CraftPreviewPacket::class) { packet, _ -> packet.handleOnClient() }
			clientbound(CraftableListPacket::class) { packet, _ -> packet.handleOnClient() }
		}
		serverbound(UpdateSortingRoutingPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(RequestTerminalSearchResultsPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(RequestWarehouseDefragPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(TerminalItemWithdrawRequestPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(TerminalItemDepositRequestPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(SetGhostSlotPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(OpenFilterCardEditorPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(UpdateFilterCardFieldPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(UpdateFilterCardModePacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(RequestCraftPreviewPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(CraftingRequestPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(RequestCraftableListPacket::class) { packet, context -> packet.handleOnServer(context) }
		register()
	}
}
