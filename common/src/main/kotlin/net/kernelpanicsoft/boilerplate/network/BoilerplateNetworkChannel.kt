package net.kernelpanicsoft.boilerplate.network

import net.kernelpanicsoft.archie.networking.NetworkChannel
import net.kernelpanicsoft.archie.util.onClient
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate

/** Boilerplate's own network channel, separate from Archie's internal one. */
object BoilerplateNetworkChannel : NetworkChannel(Boilerplate.MOD % "main") {
	fun init() {
		onClient {
			clientbound(PipeContentsSyncPacket::class) { packet, _ -> packet.handleOnClient() }
			clientbound(GantrySyncPacket::class) { packet, _ -> packet.handleOnClient() }
			clientbound(TerminalSearchResultsPacket::class) { packet, _ -> packet.handleOnClient() }
			clientbound(CraftPreviewPacket::class) { packet, _ -> packet.handleOnClient() }
			clientbound(CraftPlanPacket::class) { packet, _ -> packet.handleOnClient() }
			clientbound(CraftableListPacket::class) { packet, _ -> packet.handleOnClient() }
			clientbound(CraftJobTreePacket::class) { packet, _ -> packet.handleOnClient() }
			clientbound(CraftGridPreviewPacket::class) { packet, _ -> packet.handleOnClient() }
			clientbound(PatternGridPreviewPacket::class) { packet, _ -> packet.handleOnClient() }
			clientbound(CraftingBufferStatusPacket::class) { packet, _ -> packet.handleOnClient() }
			clientbound(RequesterStatusPacket::class) { packet, _ -> packet.handleOnClient() }
			clientbound(PendingDeliveriesPacket::class) { packet, _ -> packet.handleOnClient() }
			clientbound(DebugNetworkSnapshotPacket::class) { packet, _ -> packet.handleOnClient() }
			clientbound(WarehouseDebugSnapshotPacket::class) { packet, _ -> packet.handleOnClient() }
			clientbound(DebugTracePacket::class) { packet, _ -> packet.handleOnClient() }
		}
		serverbound(UpdateSortingRoutingPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(UpdateFilterBatchPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(UpdateExtractionConfigPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(UpdateProviderRecursionPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(UpdateParallelStockingPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(RequestTerminalSearchResultsPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(RequestWarehouseDefragPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(TerminalItemWithdrawRequestPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(TerminalItemDepositRequestPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(SetGhostSlotPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(OpenFilterCardEditorPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(UpdateFilterCardFieldPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(UpdateFilterCardModePacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(RequestCraftPreviewPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(RequestCraftPlanPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(CraftingRequestPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(RequestCraftableListPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(CraftGridRequestPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(RequestIngredientSupplyPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(SetPatternGhostInputPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(SetPatternGhostOutputPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(EncodePatternRequestPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(RequestCraftJobTreePacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(RequestCraftGridPreviewPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(RequestPatternGridPreviewPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(SetPatternKindPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(FillPatternFromRecipePacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(RequestCraftingBufferStatusPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(RequestRequesterStatusPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(SetStockingTargetPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(SetCreativeProvidedPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(CancelCraftingBufferJobPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound(RequestPendingDeliveriesPacket::class) { packet, context -> packet.handleOnServer(context) }
		serverbound<CancelPendingDeliveryPacket> { packet, context -> packet.handleOnServer(context) }
		serverbound<UpdateRackRoutingPacket> { packet, context -> packet.handleOnServer(context) }
		serverbound<UpdateWarehouseRoutingPacket> { packet, context -> packet.handleOnServer(context) }
		serverbound(DebugFlagsPacket::class) { packet, context -> packet.handleOnServer(context) }
		register()
	}
}
