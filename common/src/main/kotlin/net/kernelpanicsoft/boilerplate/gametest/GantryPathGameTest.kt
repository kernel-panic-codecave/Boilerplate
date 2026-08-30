package net.kernelpanicsoft.boilerplate.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.warehouse.GantryState
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.phys.Vec3

/**
 * [GantryState.moveTo]'s own path construction - the ascend/traverse/descend waypoint chain, checked
 * directly rather than through a whole warehouse.
 *
 * Written for a reported bug where the head finished a retrieval parked up at rail height instead of
 * back down at the controller. `moveTo` dropped every waypoint equal to the *starting* position,
 * which silently included the final destination whenever a move ended where it began - and
 * `advanceBatch` sends the gantry home the instant a batch finishes, which for a retrieval is the
 * controller it just delivered at. The ascent survived that filter, the descent didn't.
 */
@Suppress("unused")
class GantryPathGameTest {
	/**
	 * A gantry told to go somewhere it already is must not move at all - and specifically must not be
	 * left holding an ascent with no matching descent.
	 */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testMoveToWhereItAlreadyIsQueuesNothing() {
		val home = BlockPos(1, 2, 1)
		val gantry = GantryState(Vec3.atCenterOf(home))
		gantry.moveTo(home, clearanceY = 5.0)

		assertTrue(!gantry.isMoving) {
			"Expected no motion at all when already at the target, got ${gantry.remainingPath.size} waypoints: ${gantry.remainingPath}"
		}
		succeed()
	}

	/** Whatever path is queued, its last waypoint has to be the destination itself - anything else leaves the head parked mid-air. */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testMoveToAlwaysEndsAtTheDestination() {
		val gantry = GantryState(Vec3.atCenterOf(BlockPos(1, 2, 1)))
		val target = BlockPos(3, 2, 4)
		gantry.moveTo(target, clearanceY = 5.0)

		assertTrue(gantry.remainingPath.last() == Vec3.atCenterOf(target)) {
			"Expected the path to end at the destination, got ${gantry.remainingPath.last()} (full path ${gantry.remainingPath})"
		}
		succeed()
	}

	/** Running the queued path through actually lands the head on the destination, descent included. */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testAdvancingThroughTheWholePathReachesTheDestination() {
		val gantry = GantryState(Vec3.atCenterOf(BlockPos(1, 2, 1)))
		val target = BlockPos(3, 2, 4)
		gantry.moveTo(target, clearanceY = 5.0)
		gantry.advance(1000.0)

		assertTrue(!gantry.isMoving) { "Expected the path to be exhausted, got ${gantry.remainingPath.size} waypoints left" }
		assertTrue(gantry.pos == Vec3.atCenterOf(target)) { "Expected the head to finish on the destination, got ${gantry.pos}" }
		succeed()
	}

	/** A move that only changes height still has to descend/ascend onto the destination rather than stopping at rail height. */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testVerticalOnlyMoveStillReachesTheDestination() {
		val gantry = GantryState(Vec3.atCenterOf(BlockPos(2, 5, 2)))
		val target = BlockPos(2, 2, 2)
		gantry.moveTo(target, clearanceY = 5.0)
		gantry.advance(1000.0)

		assertTrue(gantry.pos == Vec3.atCenterOf(target)) { "Expected a straight-down move to land on the destination, got ${gantry.pos}" }
		succeed()
	}
}
