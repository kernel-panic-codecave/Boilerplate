package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.kernelpanicsoft.boilerplate.pipe.entity.coalesceTravelingItems
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.Fluids

/**
 * Coverage for [coalesceTravelingItems] - the merging of cargo travelling together in one segment,
 * the pipe network's answer to what vanilla does to item entities lying on the ground.
 *
 * Driven directly rather than through a built pipe run: the function takes a list, a window and a
 * capacity, and every rule worth pinning is a property of those three. Building a world in which
 * two deliveries happen to sit the right distance apart would test the extraction schedule instead.
 */
@Suppress("unused")
class TravelingItemMergeGameTest {
	private val diamond get() = ItemResource.of(ItemStack(Items.DIAMOND))

	private fun item(amount: Long, progress: Float, path: List<BlockPos> = ROUTE, reservationId: Long? = null) =
		TravelingItem(ResourceStack(diamond, amount), Direction.NORTH, progress, path, null, null, reservationId)

	@GameTest(template = SMALL)
	fun GameTestHelper.testDeliveriesWithinTheWindowMergeIntoTheOneAhead() {
		val items = mutableListOf(item(16, 0.50f), item(16, 0.40f), item(16, 0.35f))

		val absorbed = coalesceTravelingItems(items, 0.25f) { 512L }

		assertTrue(absorbed == 2) { "Expected both followers to be absorbed, got $absorbed" }
		assertTrue(items.size == 1) { "Expected one delivery to remain, got $items" }
		assertTrue(items[0].stack.amount == 48L) { "Expected the amounts to sum, got ${items[0].stack.amount}" }
		assertTrue(items[0].progress == 0.50f) {
			"Expected the merged delivery to sit where the leading one already was - merging backward " +
				"would drag it down pipe it had travelled, got ${items[0].progress}"
		}
		succeed()
	}

	@GameTest(template = SMALL)
	fun GameTestHelper.testADeliveryOutsideTheWindowIsLeftAlone() {
		val items = mutableListOf(item(16, 0.90f), item(16, 0.10f))

		assertTrue(coalesceTravelingItems(items, 0.25f) { 512L } == 0) {
			"Expected nothing to merge across most of a segment, got $items"
		}
		assertTrue(items.size == 2) { "Expected both deliveries to survive, got $items" }
		succeed()
	}

	/**
	 * A reserved delivery is owned by one [net.kernelpanicsoft.boilerplate.pipe.hook.PendingDelivery]
	 * at the far end and has to arrive as itself - folding it into a neighbour would land the
	 * reservation's goods under someone else's name, and leave the terminal holding a placeholder
	 * nothing is coming for.
	 */
	@GameTest(template = SMALL)
	fun GameTestHelper.testAReservedDeliveryNeverMerges() {
		val items = mutableListOf(item(16, 0.50f, reservationId = 7L), item(16, 0.45f, reservationId = 8L))

		assertTrue(coalesceTravelingItems(items, 0.25f) { 512L } == 0) {
			"Expected two reserved deliveries to stay separate, got $items"
		}

		val mixed = mutableListOf(item(16, 0.50f), item(16, 0.45f, reservationId = 7L))
		assertTrue(coalesceTravelingItems(mixed, 0.25f) { 512L } == 0) {
			"Expected an unreserved delivery not to swallow a reserved one, got $mixed"
		}
		succeed()
	}

	@GameTest(template = SMALL)
	fun GameTestHelper.testDeliveriesHeadedElsewhereNeverMerge() {
		val elsewhere = listOf(BlockPos(0, 0, 9), BlockPos(0, 0, 10))

		val byRoute = mutableListOf(item(16, 0.50f), item(16, 0.45f, path = elsewhere))
		assertTrue(coalesceTravelingItems(byRoute, 0.25f) { 512L } == 0) {
			"Expected different remaining routes to stay separate, got $byRoute"
		}

		val byColour = mutableListOf(
			item(16, 0.50f),
			TravelingItem(ResourceStack(diamond, 16), Direction.NORTH, 0.45f, ROUTE, DyeColor.RED),
		)
		assertTrue(coalesceTravelingItems(byColour, 0.25f) { 512L } == 0) {
			"Expected a routing colour to keep two deliveries apart, got $byColour"
		}

		val byFace = mutableListOf(
			item(16, 0.50f),
			TravelingItem(ResourceStack(diamond, 16), Direction.NORTH, 0.45f, ROUTE, null, Direction.UP),
		)
		assertTrue(coalesceTravelingItems(byFace, 0.25f) { 512L } == 0) {
			"Expected a named destination face to keep two deliveries apart, got $byFace"
		}
		succeed()
	}

	@GameTest(template = SMALL)
	fun GameTestHelper.testMergingStopsAtTheCapacityLimit() {
		val items = mutableListOf(item(400, 0.50f), item(200, 0.45f), item(100, 0.40f))

		coalesceTravelingItems(items, 0.25f) { 512L }

		// 400 + 200 overruns 512, so the leader takes the 100 instead and the 200 travels on alone.
		assertTrue(items.size == 2) { "Expected the cap to leave a second delivery behind, got $items" }
		assertTrue(items.all { it.stack.amount <= 512L }) { "Expected nothing to exceed the cap, got $items" }
		assertTrue(items.sumOf { it.stack.amount } == 700L) { "Expected the cargo to be conserved, got $items" }
		succeed()
	}

	/**
	 * Fluids go through [net.kernelpanicsoft.boilerplate.resource.ResourceIdentity] rather than
	 * `equals`, which `FluidResource` does not implement - grouping on the resource directly would
	 * put every droplet of water in a group of its own and merge nothing at all.
	 */
	@GameTest(template = SMALL)
	fun GameTestHelper.testTwoDistinctInstancesOfOneFluidStillMerge() {
		val items = mutableListOf(
			TravelingItem(ResourceStack(FluidResource.of(Fluids.WATER), 1_000), Direction.NORTH, 0.50f, ROUTE),
			TravelingItem(ResourceStack(FluidResource.of(Fluids.WATER), 1_000), Direction.NORTH, 0.45f, ROUTE),
		)

		assertTrue(coalesceTravelingItems(items, 0.25f) { 8_000L } == 1) {
			"Expected two droplets of the same fluid to merge, got $items"
		}
		assertTrue(items.single().stack.amount == 2_000L) { "Expected a doubled droplet, got $items" }
		succeed()
	}

	@GameTest(template = SMALL)
	fun GameTestHelper.testAZeroWindowLeavesEverythingAlone() {
		val items = mutableListOf(item(16, 0.50f), item(16, 0.50f))

		assertTrue(coalesceTravelingItems(items, 0f) { 512L } == 0) {
			"Expected a zero window to disable merging outright, got $items"
		}
		assertTrue(items.size == 2) { "Expected both deliveries to survive, got $items" }
		succeed()
	}

	companion object {
		private val ROUTE = listOf(BlockPos(0, 0, 1), BlockPos(0, 0, 2))
	}
}
