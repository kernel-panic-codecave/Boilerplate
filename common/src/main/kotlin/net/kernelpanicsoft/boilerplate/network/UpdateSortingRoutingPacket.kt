package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.boilerplate.util.DirectionSerializer
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState
import net.kernelpanicsoft.boilerplate.pipe.network.PipeNetworkManager
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
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
data class UpdateSortingRoutingPacket(val pos: SBlockPos, val direction: @Serializable(with = DirectionSerializer::class) Direction, val routing: RoutingModule) {
	fun handleOnServer(context: IPacketContext) {
		val level = context.player.level() as? ServerLevel ?: return
		val tile = level.getBlockEntity(pos) as? MultipartBlockEntity ?: return
		val hookState = tile.hooks[direction.name] as? SortingHookState ?: return

		hookState.routing = routing
		tile.hooks.touch()
		if (routing.priority == RoutingModule.DEFAULT_ROUTE_PRIORITY) clearOtherDefaultRoutes(level)
		val state = level.getBlockState(pos)
		level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS)
	}

	/** Enforces at most one [RoutingModule.DEFAULT_ROUTE_PRIORITY] sorting hook per network - resets any other hook already holding the sentinel (this update's own target excepted) back to baseline priority. */
	private fun clearOtherDefaultRoutes(level: ServerLevel) {
		val manager = PipeNetworkManager.get(level)
		val networkId = manager.networkIdAt(pos) ?: return
		val network = manager.network(networkId) ?: return

		for (memberPos: BlockPos in network.members) {
			val memberTile = level.getBlockEntity(memberPos) as? MultipartBlockEntity ?: continue
			for ((directionName, entry) in memberTile.hooks) {
				if (memberPos == pos && directionName == direction.name) continue
				val otherState = entry as? SortingHookState ?: continue
				if (otherState.routing.priority != RoutingModule.DEFAULT_ROUTE_PRIORITY) continue

				otherState.routing = otherState.routing.copy(priority = 0)
				memberTile.hooks.touch()
				val memberState = level.getBlockState(memberPos)
				level.sendBlockUpdated(memberPos, memberState, memberState, Block.UPDATE_CLIENTS)
			}
		}
	}
}
