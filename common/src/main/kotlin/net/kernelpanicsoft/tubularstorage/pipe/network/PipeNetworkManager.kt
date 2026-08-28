package net.kernelpanicsoft.tubularstorage.pipe.network

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import java.util.UUID
import java.util.WeakHashMap

/**
 * The item-pipe [AbstractPipeNetworkManager] - members are [PipeRouter.isPipe] positions,
 * boundaries are [SubnetBoundary.isBoundaryEdge]. See the base class's own KDoc for the shared
 * union-find merge / chunked-rebuild mechanics.
 */
class PipeNetworkManager private constructor() : AbstractPipeNetworkManager<ItemPipeNetwork>() {
	override fun createNetwork(id: UUID): ItemPipeNetwork = ItemPipeNetwork(id)
	override fun isMember(level: ServerLevel, pos: BlockPos): Boolean = PipeRouter.isPipe(level, pos)
	override fun isBoundaryEdge(level: ServerLevel, pos: BlockPos, direction: Direction): Boolean = SubnetBoundary.isBoundaryEdge(level, pos, direction)

	companion object {
		private val byLevel = WeakHashMap<ServerLevel, PipeNetworkManager>()

		fun get(level: ServerLevel): PipeNetworkManager = byLevel.getOrPut(level) { PipeNetworkManager() }
	}
}
