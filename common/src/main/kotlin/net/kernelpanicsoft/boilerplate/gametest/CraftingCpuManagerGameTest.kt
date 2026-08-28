package net.kernelpanicsoft.boilerplate.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.crafting.CraftingCpuManager
import net.minecraft.core.BlockPos
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
