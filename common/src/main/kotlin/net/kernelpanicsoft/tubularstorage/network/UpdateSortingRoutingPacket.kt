package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.tubularstorage.pipe.entity.DirectionSerializer
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.RoutingModule
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookState
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block

/**
 * Client -> server: apply a [SortingHookState.routing] edit made in `SortingPipeScreen`. Needed
 * since a nested [net.kernelpanicsoft.archie.serialization.NBTHolder] field (see
 * [HookBlockEntity.hooks]) isn't wired into live GUI observation the way a top-level `@Sync`
 * field is - see `docs/design/m2-sorting-routing.md`. Resyncs on success so every nearby client's
 * own copy (including the editing player's) picks up the change.
 */
@Serializable
data class UpdateSortingRoutingPacket(val pos: SBlockPos, val direction: @Serializable(with = DirectionSerializer::class) Direction, val routing: RoutingModule) {
	fun handleOnServer(context: IPacketContext) {
		val level = context.player.level() as? ServerLevel ?: return
		val tile = level.getBlockEntity(pos) as? HookBlockEntity ?: return
		val hookState = tile.hooks[direction.name] as? SortingHookState ?: return

		hookState.routing = routing
		tile.hooks.touch()
		val state = level.getBlockState(pos)
		level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS)
	}
}
