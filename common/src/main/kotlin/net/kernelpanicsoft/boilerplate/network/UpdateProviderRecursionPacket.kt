package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.HookHolderState
import net.kernelpanicsoft.boilerplate.util.DirectionSerializer
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block

/**
 * Client -> server: apply a [HookHolderState.recursive] edit made in `SortingHookScreen`.
 *
 * Its own packet rather than a field on [UpdateSortingRoutingPacket], because it is not part of
 * [net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule] and only one hook type has it - see
 * [UpdateFilterBatchPacket], which is a separate packet for the same reason. Resyncs on success so
 * every nearby client's copy picks the change up.
 */
@Serializable
data class UpdateProviderRecursionPacket(
	val pos: SBlockPos,
	val direction: @Serializable(with = DirectionSerializer::class) Direction,
	val recursive: Boolean,
) {
	fun handleOnServer(context: IPacketContext) {
		val level = context.player.level() as? ServerLevel ?: return
		val tile = level.getBlockEntity(pos) as? MultipartBlockEntity ?: return
		val hookState = tile.hooks[direction.name] ?: return
		// Only a hook that provides has anything to be recursive about - see HookHolderState.recursive.
		if (hookState.fromRegistry?.providesItems != true) return

		hookState.recursive = recursive
		tile.hooks.touch()
		val state = level.getBlockState(pos)
		level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS)
	}
}
