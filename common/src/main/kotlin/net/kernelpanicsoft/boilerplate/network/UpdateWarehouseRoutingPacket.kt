package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.kernelpanicsoft.boilerplate.warehouse.entity.WarehouseControllerBlockEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block

/**
 * Client -> server: apply a [WarehouseControllerBlockEntity.routing] edit made in
 * [net.kernelpanicsoft.boilerplate.warehouse.gui.WarehouseControllerScreen]. The controller's filter
 * slot is a real vanilla slot (see [net.kernelpanicsoft.boilerplate.warehouse.gui.WarehouseControllerMenu.registerSlotHandlers]),
 * so vanilla's own container syncing carries it; only the routing (mode/priority) needs this
 * packet, the same [net.kernelpanicsoft.boilerplate.network.UpdateRackRoutingPacket] pattern.
 * Resyncs on success so every nearby client's own copy (including the editing player's) picks up
 * the change.
 */
@Serializable
data class UpdateWarehouseRoutingPacket(val pos: SBlockPos, val routing: RoutingModule) {
	fun handleOnServer(context: IPacketContext) {
		val level = context.player.level() as? ServerLevel ?: return
		val tile = level.getBlockEntity(pos) as? WarehouseControllerBlockEntity ?: return

		tile.routing = routing
		val state = level.getBlockState(pos)
		level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS)
	}
}