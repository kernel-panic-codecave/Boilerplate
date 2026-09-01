package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.pipe.client.PipeContentsClientCache
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.Fluids

/**
 * Coverage for the block-boundary hand-off in [PipeContentsClientCache] - the client-side half of
 * what keeps a travelling item or fluid continuously visible as it crosses between segments.
 *
 * A hand-off is two sync packets, and until both land the departing segment's cached copy has
 * already run past its exit face while the receiving segment's copy has not appeared. Without the
 * cache making that transfer itself, the item is drawn by nobody for the whole of that window,
 * which is visible as a flicker at every single boundary. These tests pin the transfer rather than
 * the rendering, since the rendering cannot be exercised headlessly.
 *
 * Everything here drives the cache directly - it is a plain object over synced data, with no
 * client-only types in its signature - so no world interaction is needed beyond a place to run.
 */
@Suppress("unused")
class PipeContentsHandoffGameTest {
	@GameTest(template = SMALL)
	fun GameTestHelper.testItemTransfersToTheNextSegmentAsItCrossesTheBoundary() {
		val from = BlockPos(400, 100, 400)
		val to = from.south()
		val onward = to.south()
		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))

		// One tick short of the boundary, heading `from` -> `to` -> `onward`.
		PipeContentsClientCache.update(
			from,
			listOf(TravelingItem(ResourceStack(diamond, 4), Direction.NORTH, 0.95f, listOf(to, onward))),
			1f,
			0L,
		)

		try {
			assertTrue(PipeContentsClientCache.get(from, 0.0).size == 1) {
				"Expected the departing segment to still hold the item before the boundary"
			}
			assertTrue(PipeContentsClientCache.get(to, 0.0).isEmpty()) {
				"Expected the receiving segment to hold nothing before the boundary"
			}

			// Two ticks on, progress is 0.95 + 2*0.05 = 1.05 - just past the exit face.
			val departed = PipeContentsClientCache.get(from, 2.0)
			val arrived = PipeContentsClientCache.get(to, 2.0)

			assertTrue(departed.isEmpty()) {
				"Expected the item to have left the departing segment once past its exit face, got $departed"
			}
			assertTrue(arrived.size == 1) {
				"Expected the item to be drawn by the receiving segment the instant it crosses - " +
					"drawn by neither is exactly the boundary flicker this guards, got $arrived"
			}

			val item = arrived[0]
			assertTrue(item.progress in 0f..0.2f) {
				"Expected the overshoot to carry across as a small entry progress, got ${item.progress}"
			}
			assertTrue(item.path == listOf(onward)) {
				"Expected the consumed hop to be dropped from the remaining path, got ${item.path}"
			}
			assertTrue(item.fromDirection == Direction.NORTH) {
				"Expected the item to enter through the face toward the segment it came from, got ${item.fromDirection}"
			}
			assertTrue(item.stack.amount == 4L) { "Expected the hand-off to preserve the stack, got ${item.stack.amount}" }
		} finally {
			PipeContentsClientCache.remove(from)
			PipeContentsClientCache.remove(to)
		}
		succeed()
	}

	/**
	 * The window where both packets have landed must not draw the item twice - once from the
	 * receiving segment's own entry and again as a prediction from the departing one.
	 *
	 * Uses a fluid deliberately: the de-duplication compares resources through `ResourceIdentity`,
	 * and `FluidResource` has no value equality of its own, so a naive `==` would fail to match and
	 * every fluid hand-off would double-draw.
	 */
	@GameTest(template = SMALL)
	fun GameTestHelper.testAFluidIsNotDrawnTwiceWhileBothPacketsAreInFlight() {
		val from = BlockPos(400, 100, 500)
		val to = from.south()
		val onward = to.south()
		val water = FluidResource.of(Fluids.WATER)

		// The departing segment's stale copy still lists the fluid, past its exit face...
		PipeContentsClientCache.update(
			from,
			listOf(TravelingItem(ResourceStack(water, 1000), Direction.NORTH, 0.95f, listOf(to, onward))),
			1f,
			0L,
		)
		// ...while the receiving segment's own packet, describing the very same delivery, has landed.
		PipeContentsClientCache.update(
			to,
			listOf(TravelingItem(ResourceStack(FluidResource.of(Fluids.WATER), 1000), Direction.NORTH, 0.05f, listOf(onward))),
			1f,
			1L,
		)

		try {
			val arrived = PipeContentsClientCache.get(to, 2.0)
			assertTrue(arrived.size == 1) {
				"Expected exactly one fluid on the receiving segment while both packets are live, got ${arrived.size}"
			}
		} finally {
			PipeContentsClientCache.remove(from)
			PipeContentsClientCache.remove(to)
		}
		succeed()
	}
}
