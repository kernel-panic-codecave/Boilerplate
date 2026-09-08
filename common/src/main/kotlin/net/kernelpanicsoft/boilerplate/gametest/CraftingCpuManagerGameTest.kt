package net.kernelpanicsoft.boilerplate.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.crafting.CraftingBufferEncasementType
import net.kernelpanicsoft.boilerplate.crafting.CraftingCpuManager
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel

/**
 * GameTest coverage for [CraftingCpuManager] - adjacent Crafting Buffer encased pipe segments
 * clustering into one Crafting CPU with a deterministic leader, a broken member splitting its
 * cluster, and only cuboid (bounding-box-complete) arrangements counting as valid.
 */
@Suppress("unused")
class CraftingCpuManagerGameTest {
	/**
	 * A Crafting CPU sitting *beside* the network, with no arms formed toward it, is not somewhere a
	 * terminal may submit a job.
	 *
	 * The regression this locks in cost an evening: [net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment.reachablePipes]
	 * hands back every position it merely examined - adjacent non-pipes included, deliberately,
	 * since its other callers need those neighbours - and CPU discovery used to read that set
	 * directly. An unconnected CPU therefore looked like a perfectly good submission target from the
	 * terminal, took the job, and then resolved its *own* providers, warehouses and pattern sources
	 * from its own isolated position, reached nothing, and sat forever reporting "No free pattern
	 * provider" while the pattern was plainly sitting in a provider the terminal could see.
	 *
	 * Deliberately built with [setBlock] and no `updateFromNeighbourShapes` fix-up afterwards -
	 * which is exactly what [placeCraftingBuffer] exists to do, and why it says so - so the segment
	 * lands genuinely adjacent to the pipe with every connection bit false, the same shape a
	 * half-formed one has in a real world.
	 */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testAnUnconnectedCpuIsNotOfferedAsASubmissionTarget() {
		val pipePos = BlockPos(3, 2, 3)
		val connectedCpuPos = BlockPos(4, 2, 3)
		val strandedCpuPos = BlockPos(2, 2, 3)

		setBlock(pipePos, BlockRegistry.Pipe.defaultBlockState())
		val connected = placeCraftingBuffer(connectedCpuPos)

		// The stranded one: a buffer on a segment whose arms were never recomputed, so it touches
		// the pipe without connecting to it.
		setBlock(strandedCpuPos, BlockRegistry.Multipart.defaultBlockState())
		val strandedTile = getBlockEntity(strandedCpuPos) as MultipartBlockEntity
		strandedTile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val strandedState = CraftingBufferEncasementType.createState()
		strandedTile.encasement.value = strandedState
		CraftingBufferEncasementType.onAttached(level, strandedTile.blockPos, strandedTile, strandedState)

		val serverLevel = level as ServerLevel
		val leaders = RequestFulfillment.reachableCraftingCpus(serverLevel, absolutePos(pipePos)).map { it.leaderPos }

		assertTrue(absolutePos(connectedCpuPos) in leaders) {
			"Expected the connected CPU at ${absolutePos(connectedCpuPos)} to be offered, got $leaders"
		}
		assertTrue(absolutePos(strandedCpuPos) !in leaders) {
			"Expected the unconnected CPU at ${absolutePos(strandedCpuPos)} to be refused - a job submitted to it can never complete - got $leaders"
		}
		// Guards the reason rather than only the symptom: the point is that it cannot reach back.
		assertTrue(RequestFulfillment.connectedPipes(serverLevel, absolutePos(strandedCpuPos)).size == 1) {
			"Expected the unconnected CPU to walk to nothing but itself, got ${RequestFulfillment.connectedPipes(serverLevel, absolutePos(strandedCpuPos))}"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testSingleSegmentIsItsOwnCluster() {
		val pos = absolutePos(BlockPos(3, 2, 3))
		placeCraftingBuffer(BlockPos(3, 2, 3))

		val cluster = CraftingCpuManager.get(level as ServerLevel).clusterOf(level as ServerLevel, pos)
		assertTrue(cluster.members == listOf(pos) && cluster.leader == pos) {
			"Expected a lone encased segment to form a one-member cluster with itself as leader, got $cluster"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testAdjacentSegmentsFormOneClusterWithDeterministicLeader() {
		val posA = absolutePos(BlockPos(3, 2, 3))
		val posB = absolutePos(BlockPos(4, 2, 3))
		placeCraftingBuffer(BlockPos(3, 2, 3))
		placeCraftingBuffer(BlockPos(4, 2, 3))

		val manager = CraftingCpuManager.get(level as ServerLevel)
		val clusterFromA = manager.clusterOf(level as ServerLevel, posA)
		val clusterFromB = manager.clusterOf(level as ServerLevel, posB)
		val expectedLeader = if (posA.asLong() < posB.asLong()) posA else posB

		assertTrue(clusterFromA.members.toSet() == setOf(posA, posB)) {
			"Expected both segments in one cluster, got ${clusterFromA.members}"
		}
		assertTrue(clusterFromA.leader == expectedLeader && clusterFromB.leader == expectedLeader) {
			"Expected both members to agree on the same deterministic leader, got ${clusterFromA.leader} / ${clusterFromB.leader}, expected $expectedLeader"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testBreakingAMemberSplitsTheCluster() {
		val posB = absolutePos(BlockPos(4, 2, 3))
		placeCraftingBuffer(BlockPos(3, 2, 3))
		placeCraftingBuffer(BlockPos(4, 2, 3))

		val manager = CraftingCpuManager.get(level as ServerLevel)
		manager.clusterOf(level as ServerLevel, posB)
		destroyBlock(BlockPos(3, 2, 3))

		val clusterAfter = manager.clusterOf(level as ServerLevel, posB)
		assertTrue(clusterAfter.members == listOf(posB) && clusterAfter.leader == posB) {
			"Expected the surviving segment to form its own one-member cluster after its neighbor broke, got $clusterAfter"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testCombinedStorageConcatenatesEveryMembersOwnSlots() {
		val tileA = placeCraftingBuffer(BlockPos(3, 2, 3))
		val tileB = placeCraftingBuffer(BlockPos(4, 2, 3))

		val combined = tileA.craftingBuffer.combinedStorage(tileA)
		assertTrue(combined.size() == tileA.craftingBuffer.localStorage.size() + tileB.craftingBuffer.localStorage.size()) {
			"Expected the cluster's combined storage to concatenate both members' own slots, got size=${combined.size()}"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testLShapedArrangementIsInvalid() {
		placeCraftingBuffer(BlockPos(3, 2, 3))
		placeCraftingBuffer(BlockPos(4, 2, 3))
		placeCraftingBuffer(BlockPos(3, 2, 4))

		val cluster = CraftingCpuManager.get(level as ServerLevel).clusterOf(level as ServerLevel, absolutePos(BlockPos(3, 2, 3)))
		assertTrue(!cluster.valid) {
			"Expected an L-shaped arrangement spanning a 2x1x2 box with one corner missing to be an invalid cluster, got $cluster"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testCompletingTheCuboidMakesTheClusterValidAgain() {
		val posA = absolutePos(BlockPos(3, 2, 3))
		placeCraftingBuffer(BlockPos(3, 2, 3))
		placeCraftingBuffer(BlockPos(4, 2, 3))
		placeCraftingBuffer(BlockPos(3, 2, 4))

		val manager = CraftingCpuManager.get(level as ServerLevel)
		val before = manager.clusterOf(level as ServerLevel, posA)
		assertTrue(!before.valid) { "Expected the incomplete arrangement to start out invalid, got $before" }

		placeCraftingBuffer(BlockPos(4, 2, 4))

		val after = manager.clusterOf(level as ServerLevel, posA)
		assertTrue(after.valid && after.members.size == 4 && after.leader == minOf(posA, absolutePos(BlockPos(4, 2, 4)), compareBy { it.asLong() })) {
			"Expected the completed 2x2x1 cuboid to form one valid four-member cluster, got $after"
		}
		succeed()
	}
}
