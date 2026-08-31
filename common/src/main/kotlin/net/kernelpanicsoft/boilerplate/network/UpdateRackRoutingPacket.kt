package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState
import net.kernelpanicsoft.boilerplate.warehouse.rack.RackBlockEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block

/**
 * Client -> server: apply a [SortingHookState.routing] edit made in `SortingPipeScreen`. Needed
 * since a nested [net.kernelpanicsoft.archie.serialization.NBTHolder] field (see
 * [MultipartBlockEntity.hooks]) isn't wired into live GUI observation the way a top-level `@Sync`
 * field is - see `docs/design/m2-sorting-routing.md`. Resyncs on success so every nearby client's
 * own copy (including the editing player's) picks up the change.
 */
@Serializable
data class UpdateRackRoutingPacket(val pos: SBlockPos, val routing: RoutingModule) {
	fun handleOnServer(context: IPacketContext) {
		val level = context.player.level() as? ServerLevel ?: return
		val tile = level.getBlockEntity(pos) as? RackBlockEntity ?: return

		tile.routing = routing
		val state = level.getBlockState(pos)
		level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS)
	}
}
