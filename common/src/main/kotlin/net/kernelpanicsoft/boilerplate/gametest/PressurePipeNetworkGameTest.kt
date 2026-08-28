package net.kernelpanicsoft.boilerplate.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.power.network.PressurePipeNetworkManager
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks

/**
 * GameTest coverage for [PressurePipeNetworkManager]'s union-find merge on placement and
 * chunked-BFS rebuild on removal - mirrors [PipeNetworkGameTest] exactly, proving
 * [net.kernelpanicsoft.boilerplate.pipe.network.AbstractPipeNetworkManager] genuinely
 * generalizes to a second, independent network kind rather than being item-pipe-shaped.
 */
@Suppress("unused")
class PressurePipeNetworkGameTest {
	@GameTest(template = SMALL)
	fun GameTestHelper.testAdjacentPressurePipesMergeIntoOneNetwork() {
		val posA = BlockPos(0, 2, 0)
		val posB = BlockPos(0, 2, 1)
		setBlock(posA, BlockRegistry.PressurePipe.defaultBlockState())
		setBlock(posB, BlockRegistry.PressurePipe.defaultBlockState())

		val serverLevel = level as ServerLevel
		val a = absolutePos(posA)
		val b = absolutePos(posB)
		val manager = PressurePipeNetworkManager.get(serverLevel)
		manager.ensureRegistered(serverLevel, a)
		manager.ensureRegistered(serverLevel, b)

		val networkA = manager.networkIdAt(a)
		assertTrue(networkA != null) { "Expected pressure pipe at $posA to be registered in a network" }
		assertTrue(networkA == manager.networkIdAt(b)) { "Expected adjacent pressure pipes to share one network" }
		succeed()
	}

	@GameTest(template = SMALL)
	fun GameTestHelper.testRemovingConnectorPressurePipeSplitsNetwork() {
		val posA = BlockPos(0, 2, 0)
		val posB = BlockPos(0, 2, 1)
		val posC = BlockPos(0, 2, 2)
		setBlock(posA, BlockRegistry.PressurePipe.defaultBlockState())
		setBlock(posB, BlockRegistry.PressurePipe.defaultBlockState())
		setBlock(posC, BlockRegistry.PressurePipe.defaultBlockState())

		val serverLevel = level as ServerLevel
		val a = absolutePos(posA)
		val b = absolutePos(posB)
		val c = absolutePos(posC)
		val manager = PressurePipeNetworkManager.get(serverLevel)
		manager.ensureRegistered(serverLevel, a)
		manager.ensureRegistered(serverLevel, b)
		manager.ensureRegistered(serverLevel, c)
		assertTrue(manager.networkIdAt(a) == manager.networkIdAt(c)) { "Expected all three pressure pipes to start in one network" }

		setBlock(posB, Blocks.AIR.defaultBlockState())
		manager.onRemoved(b)

		runAfterDelay(5) {
			val networkA = manager.networkIdAt(a)
			val networkC = manager.networkIdAt(c)
			assertTrue(networkA != null && networkC != null) { "Expected both remaining pressure pipes to still be registered after the rebuild" }
			assertTrue(networkA != networkC) { "Expected removing the connecting pressure pipe to split the network" }
			succeed()
		}
	}
}
