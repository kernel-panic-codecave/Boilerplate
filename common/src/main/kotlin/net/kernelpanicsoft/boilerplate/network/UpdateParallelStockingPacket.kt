package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.ParallelStocking
import net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookState
import net.kernelpanicsoft.boilerplate.util.DirectionSerializer
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block

/**
 * Client -> server: apply a [RequesterHookState.parallel] edit made in `RequesterHookScreen`.
 *
 * Its own packet for the same reason [UpdateFilterBatchPacket] is: it belongs to one hook type and
 * is not part of [net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule]. Resyncs on success so
 * every nearby client's copy picks the change up.
 */
@Serializable
data class UpdateParallelStockingPacket(
	val pos: SBlockPos,
	val direction: @Serializable(with = DirectionSerializer::class) Direction,
	val parallel: ParallelStocking,
) {
	fun handleOnServer(context: IPacketContext) {
		val level = context.player.level() as? ServerLevel ?: return
		val tile = level.getBlockEntity(pos) as? MultipartBlockEntity ?: return
		val hookState = tile.hooks[direction.name] as? RequesterHookState ?: return

		hookState.parallel = parallel
		tile.hooks.touch()
		val state = level.getBlockState(pos)
		level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS)
	}
}
