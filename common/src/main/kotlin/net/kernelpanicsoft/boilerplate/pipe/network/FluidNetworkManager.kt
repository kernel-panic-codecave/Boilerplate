package net.kernelpanicsoft.boilerplate.pipe.network

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import java.util.UUID
import java.util.WeakHashMap

/**
 * The fluid-pipe [AbstractPipeNetworkManager] - members are [FluidNetworkType] positions. See the
 * base class's own KDoc for the shared union-find merge / chunked-rebuild mechanics.
 */
class FluidNetworkManager private constructor() : AbstractPipeNetworkManager<FluidPipeNetwork>() {
	override fun createNetwork(id: UUID): FluidPipeNetwork = FluidPipeNetwork(id)
	override fun isMember(level: ServerLevel, pos: BlockPos): Boolean = FluidPipeRouter.isPipe(level, pos)

	companion object {
		private val byLevel = WeakHashMap<ServerLevel, FluidNetworkManager>()

		fun get(level: ServerLevel): FluidNetworkManager = byLevel.getOrPut(level) { FluidNetworkManager() }
	}
}
