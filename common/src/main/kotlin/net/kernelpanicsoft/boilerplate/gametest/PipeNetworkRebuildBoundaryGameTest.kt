package net.kernelpanicsoft.boilerplate.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.ProviderHookType
import net.kernelpanicsoft.boilerplate.pipe.network.PipeNetworkManager
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks

/**
 * GameTest coverage for [PipeNetworkManager]'s chunked-BFS rebuild ([net.kernelpanicsoft.boilerplate.pipe.network.AbstractPipeNetworkManager.tick])
 * respecting [net.kernelpanicsoft.boilerplate.pipe.network.SubnetBoundary.isBoundaryEdge] - the
 * rebuild's own `rebuildRemaining` set is one shared, level-wide pool (`AbstractPipeNetworkManager.onRemoved`
 * unions every pending invalidation's own former members into it), so two *unrelated* invalidations
 * landing in the same pending batch - a hook attach/detach on one side of a subnet boundary, and any
 * other pipe change on the far side, close enough together that neither's rebuild has fully drained
 * yet - used to let the rebuild's own raw-adjacency flood-fill (previously ignoring
 * `isBoundaryEdge` entirely, unlike [PipeNetworkManager.ensureRegistered]'s own merge) walk straight
 * across a boundary that should never merge, wrongly reuniting the two sides.
 */
@Suppress("unused")
class PipeNetworkRebuildBoundaryGameTest {
	@GameTest(template = SMALL)
	fun GameTestHelper.testRebuildNeverMergesAcrossASubnetBoundaryEvenWhenBothSidesShareOnePendingBatch() {
		val posA0 = BlockPos(0, 2, 0)
		val posA = BlockPos(1, 2, 0)
		val posB = BlockPos(2, 2, 0)
		val posC = BlockPos(3, 2, 0)
		val posC0 = BlockPos(4, 2, 0)

		setBlock(posA0, BlockRegistry.Pipe.defaultBlockState())

		setBlock(posA, BlockRegistry.Multipart.defaultBlockState())
		val tileA = getBlockEntity(posA) as MultipartBlockEntity
		tileA.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		tileA.hooks.getOrPut(Direction.EAST.name) { InterfaceHookType.createState() }

		setBlock(posB, BlockRegistry.Multipart.defaultBlockState())
		val tileB = getBlockEntity(posB) as MultipartBlockEntity
		tileB.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		tileB.hooks.getOrPut(Direction.WEST.name) { ProviderHookType.createState() }

		setBlock(posC, BlockRegistry.Pipe.defaultBlockState())
		setBlock(posC0, BlockRegistry.Pipe.defaultBlockState())

		val serverLevel = level as ServerLevel
		val a0 = absolutePos(posA0)
		val a = absolutePos(posA)
		val b = absolutePos(posB)
		val c = absolutePos(posC)
		val c0 = absolutePos(posC0)

		val manager = PipeNetworkManager.get(serverLevel)
		manager.ensureRegistered(serverLevel, a0)
		manager.ensureRegistered(serverLevel, a)
		manager.ensureRegistered(serverLevel, b)
		manager.ensureRegistered(serverLevel, c)
		manager.ensureRegistered(serverLevel, c0)

		assertTrue(manager.networkIdAt(a0) == manager.networkIdAt(a)) { "Expected posA0/posA to start in one network" }
		assertTrue(manager.networkIdAt(b) == manager.networkIdAt(c) && manager.networkIdAt(c) == manager.networkIdAt(c0)) { "Expected posB/posC/posC0 to start in one network" }
		assertTrue(manager.networkIdAt(a) != manager.networkIdAt(b)) { "Expected the Interface-hook boundary between posA and posB to keep them in separate networks from the start" }

		// Invalidate both sides of the boundary in the same pending batch, before either's own
		// rebuild has a chance to drain - the exact shape a hook attach/detach on one side, close
		// together with any other pipe change on the far side, produces in real play.
		setBlock(posA0, Blocks.AIR.defaultBlockState())
		manager.onRemoved(a0)
		setBlock(posC0, Blocks.AIR.defaultBlockState())
		manager.onRemoved(c0)

		runAfterDelay(5) {
			val networkA = manager.networkIdAt(a)
			val networkB = manager.networkIdAt(b)
			val networkC = manager.networkIdAt(c)
			assertTrue(networkA != null && networkB != null && networkC != null) { "Expected posA/posB/posC to still be registered after the rebuild" }
			assertTrue(networkA != networkB) { "Expected the rebuild to keep respecting the Interface-hook boundary, not merge posA/posB back together just because both sides shared one pending rebuild batch" }
			assertTrue(networkB == networkC) { "Expected posB/posC to still be merged with each other after the rebuild" }
			succeed()
		}
	}
}
