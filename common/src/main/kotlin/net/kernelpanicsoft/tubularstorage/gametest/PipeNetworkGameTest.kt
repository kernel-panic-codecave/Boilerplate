package net.kernelpanicsoft.tubularstorage.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.pipe.network.PipeNetworkManager
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks

/**
 * GameTest coverage for [PipeNetworkManager]'s union-find merge on placement and chunked-BFS
 * rebuild on removal - the two behaviors M1's pipe network is built on.
 */
@Suppress("unused")
class PipeNetworkGameTest {
	@GameTest(template = SMALL)
	fun GameTestHelper.testAdjacentPipesMergeIntoOneNetwork() {
		val posA = BlockPos(0, 2, 0)
		val posB = BlockPos(0, 2, 1)
		setBlock(posA, BlockRegistry.Pipe.defaultBlockState())
		setBlock(posB, BlockRegistry.Pipe.defaultBlockState())

		val serverLevel = level as ServerLevel
		val a = absolutePos(posA)
		val b = absolutePos(posB)
		val manager = PipeNetworkManager.get(serverLevel)
		manager.ensureRegistered(serverLevel, a)
		manager.ensureRegistered(serverLevel, b)

		val networkA = manager.networkIdAt(a)
		assertTrue(networkA != null) { "Expected pipe at $posA to be registered in a network" }
		assertTrue(networkA == manager.networkIdAt(b)) { "Expected adjacent pipes to share one network" }
		succeed()
	}

	@GameTest(template = SMALL)
	fun GameTestHelper.testRemovingConnectorPipeSplitsNetwork() {
		val posA = BlockPos(0, 2, 0)
		val posB = BlockPos(0, 2, 1)
		val posC = BlockPos(0, 2, 2)
		setBlock(posA, BlockRegistry.Pipe.defaultBlockState())
		setBlock(posB, BlockRegistry.Pipe.defaultBlockState())
		setBlock(posC, BlockRegistry.Pipe.defaultBlockState())

		val serverLevel = level as ServerLevel
		val a = absolutePos(posA)
		val b = absolutePos(posB)
		val c = absolutePos(posC)
		val manager = PipeNetworkManager.get(serverLevel)
		manager.ensureRegistered(serverLevel, a)
		manager.ensureRegistered(serverLevel, b)
		manager.ensureRegistered(serverLevel, c)
		assertTrue(manager.networkIdAt(a) == manager.networkIdAt(c)) { "Expected all three pipes to start in one network" }

		setBlock(posB, Blocks.AIR.defaultBlockState())
		manager.onRemoved(b)

		// PipeNetworkManager's split rebuild is chunked across TubularStorage's own
		// TickEvent.SERVER_LEVEL_POST listener, not run synchronously by onRemoved - give it a few
		// real server ticks to drain (REBUILD_BUDGET_PER_TICK is 500, so one tick is enough for two
		// positions; a handful of ticks of margin keeps this from being timing-flaky).
		runAfterDelay(5) {
			val networkA = manager.networkIdAt(a)
			val networkC = manager.networkIdAt(c)
			assertTrue(networkA != null && networkC != null) { "Expected both remaining pipes to still be registered after the rebuild" }
			assertTrue(networkA != networkC) { "Expected removing the connecting pipe to split the network" }
			succeed()
		}
	}
}
