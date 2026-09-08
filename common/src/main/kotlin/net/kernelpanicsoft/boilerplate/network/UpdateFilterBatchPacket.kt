package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.FilterHookState
import net.kernelpanicsoft.boilerplate.util.DirectionSerializer
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block

/**
 * Client -> server: set [FilterHookState.batchSize], the multiple this face delivers in.
 *
 * A nested [net.kernelpanicsoft.archie.serialization.NBTHolder] field is not wired into live GUI
 * observation the way a top-level `@Sync` field is, so an edit needs a packet of its own - the same
 * reason [UpdateSortingRoutingPacket] exists, and the same `hooks.touch()` + resync afterwards.
 *
 * Nothing has to be handed back when the size shrinks: the hook holds no items of its own, so a
 * surplus simply stays wherever it was already waiting - the barrel or warehouse feeding the line.
 */
@Serializable
data class UpdateFilterBatchPacket(
	val pos: SBlockPos,
	val direction: @Serializable(with = DirectionSerializer::class) Direction,
	val size: Long,
) {
	fun handleOnServer(context: IPacketContext) {
		val level = context.player.level() as? ServerLevel ?: return
		val tile = level.getBlockEntity(pos) as? MultipartBlockEntity ?: return
		val hookState = tile.hooks[direction.name] as? FilterHookState ?: return

		hookState.batchSize = size
		tile.hooks.touch()

		val state = level.getBlockState(pos)
		level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS)
	}
}
