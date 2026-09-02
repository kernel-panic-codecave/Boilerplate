package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.fluid.util.FluidAmounts
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.network.ResourceIdentity
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FluidConditionState
import net.kernelpanicsoft.boilerplate.pipe.network.FluidPipeRouter
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.minecraft.world.item.ItemStack
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.warehouse.Bounds
import net.kernelpanicsoft.boilerplate.warehouse.DeliveryTarget
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseDefragPlanner
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseIndex
import net.kernelpanicsoft.boilerplate.warehouse.tank.FluidTankBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.material.Fluids

/**
 * The warehouse is **resource-kind agnostic**: a tank inside a bound volume is an ordinary rack, and
 * the gantry moves fluid out of and into it exactly as it moves items.
 *
 * Nothing in the warehouse names items or fluids any more - it asks the registry which kinds have a
 * [net.kernelpanicsoft.boilerplate.network.ResourceStorageKind] and drives them all the same way.
 * These tests pin that from the outside: they only ever use the public warehouse API, so they would
 * pass unchanged for a third kind an addon registers.
 */
@Suppress("unused")
class WarehouseFluidGameTest {
	private val bucket: Long get() = FluidAmounts.toPlatformAmount(1000L)

	private val water: FluidResource get() = FluidResource.of(Fluids.WATER)

	/** A controller with a bound volume, plus a tank inside it - the smallest fluid warehouse. */
	private fun GameTestHelper.layOutFluidWarehouse(tankPos: BlockPos, fill: Long = 0L): WarehouseControllerBlockEntity {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(tankPos, BlockRegistry.FluidTank.defaultBlockState())
		if (fill > 0) (getBlockEntity(tankPos) as FluidTankBlockEntity).storage.insert(water, fill, false)

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))
		return controller
	}

	/**
	 * A tank in the volume is indexed, and its fluid is findable by a *separately built* handle -
	 * the [ResourceIdentity] keying the index depends on, since `FluidResource` has no `equals`.
	 */
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testATankInTheVolumeIsIndexedAsARack() {
		val tankPos = BlockPos(3, 2, 4)
		val controller = layOutFluidWarehouse(tankPos, fill = bucket * 2)

		succeedWhen {
			val slots = controller.index.slotsFor(FluidResource.of(Fluids.WATER))
			assertTrue(slots.any { it.pos == absolutePos(tankPos) && it.amount == bucket * 2 }) {
				"Expected the tank's two buckets of water to be indexed as a rack slot, got $slots"
			}
		}
	}

	/** The gantry pulls fluid out of a tank into the controller's own outbound buffer - the fluid twin of `testEnqueuedRetrieveDeliversToOutboundBuffer`. */
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testTheGantryRetrievesFluidFromATank() {
		val tankPos = BlockPos(3, 2, 4)
		val controller = layOutFluidWarehouse(tankPos, fill = bucket * 2)

		val slot = WarehouseIndex.RackSlotRef(absolutePos(tankPos), null, bucket * 2)
		controller.enqueueRetrieve(slot, ResourceStack(water as ResourceComponent, bucket * 2))

		succeedWhen {
			val outbound = controller.outboundFor(ResourceKindRegistry.Fluid)
			assertTrue(outbound != null) { "Expected the controller to have a fluid outbound buffer" }
			val held = (0 until outbound!!.size()).sumOf { i ->
				if (ResourceIdentity.of(outbound.getResource(i) as ResourceComponent) == ResourceIdentity.of(water)) outbound.getAmount(i) else 0L
			}
			assertTrue(held == bucket * 2) { "Expected both buckets to land in the fluid outbound buffer, got $held" }

			val tank = getBlockEntity(tankPos) as FluidTankBlockEntity
			assertTrue(tank.storage.getAmount(0) == 0L) { "Expected the tank to have been drained by the retrieval, got ${tank.storage.getAmount(0)}" }
		}
	}

	/** Put-away in reverse: fluid sitting in the controller's inbound buffer is stowed into a tank with room. */
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testPutAwayStowsFluidIntoATank() {
		val tankPos = BlockPos(3, 2, 4)
		val controller = layOutFluidWarehouse(tankPos)

		val inbound = controller.inboundFor(ResourceKindRegistry.Fluid)
		assertTrue(inbound != null) { "Expected the controller to have a fluid inbound buffer" }
		ResourceKindRegistry.Fluid.storage!!.insert(inbound!!, water, bucket, false)

		succeedWhen {
			val tank = getBlockEntity(tankPos) as FluidTankBlockEntity
			assertTrue(tank.storage.getAmount(0) == bucket) {
				"Expected the bucket in the inbound buffer to be put away into the tank, got ${tank.storage.getAmount(0)}"
			}
		}
	}

	/**
	 * Defragmentation consolidates two part-full tanks of the same fluid.
	 *
	 * This was deliberately item-only before, on the reasoning that partial tanks don't waste a slot
	 * the way partial item stacks do. In a warehouse whose racks *are* tanks that is backwards: a
	 * tank holds one fluid, so two half-full tanks of water occupy two of them and deny the second
	 * to anything else.
	 */
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testDefragConsolidatesPartFullTanks() {
		val tankOnePos = BlockPos(3, 2, 4)
		val tankTwoPos = BlockPos(4, 2, 4)
		val controller = layOutFluidWarehouse(tankOnePos, fill = bucket * 2)
		setBlock(tankTwoPos, BlockRegistry.FluidTank.defaultBlockState())
		(getBlockEntity(tankTwoPos) as FluidTankBlockEntity).storage.insert(water, bucket, false)

		succeedWhen {
			assertTrue(controller.index.slotsFor(FluidResource.of(Fluids.WATER)).size == 2) {
				"Expected both tanks to be indexed before defragmentation is planned"
			}
			val moves = WarehouseDefragPlanner.plan(level as ServerLevel, controller)
			assertTrue(moves.any { ResourceIdentity.of(it.stack.resource) == ResourceIdentity.of(water) }) {
				"Expected a Move consolidating the two part-full water tanks, got $moves"
			}
		}
	}

	/**
	 * A **crafting job's** fluid raw material is claimed straight off a warehouse tank.
	 *
	 * This is the gap that was open when fluid patterns landed: a fluid ingredient had to be
	 * reachable through a provider hook, because warehouse retrieval was item-typed and a fluid
	 * retrieve meant a gantry job that did not exist. It exists now, and it is the same job an item
	 * uses - so this asserts the claim through the ordinary public API rather than anything
	 * fluid-shaped.
	 */
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testACraftingClaimPullsFluidOffAWarehouseTank() {
		val tankPos = BlockPos(3, 2, 4)
		val pipePos = BlockPos(1, 2, 0)
		val controller = layOutFluidWarehouse(tankPos, fill = bucket * 3)
		setBlock(pipePos, BlockRegistry.Pipe.defaultBlockState())

		succeedWhen {
			// Only meaningful once the tank has actually been indexed - claimAndEnqueue reads the
			// index, exactly as a Crafting CPU claiming a plan's raw materials does.
			assertTrue(controller.index.slotsFor(FluidResource.of(Fluids.WATER)).isNotEmpty()) {
				"Expected the tank to be indexed before claiming against it"
			}
			val claimed = controller.claimAndEnqueue(FluidResource.of(Fluids.WATER), bucket * 2, DeliveryTarget.Pipe(absolutePos(pipePos)))
			assertTrue(claimed == bucket * 2) {
				"Expected two buckets to be claimable off the warehouse tank, got $claimed"
			}
			assertTrue(controller.pendingJobs.any { ResourceIdentity.of(it.stack.resource) == ResourceIdentity.of(water) }) {
				"Expected the claim to have queued a real gantry job carrying the water, got ${controller.pendingJobs}"
			}
		}
	}

	/**
	 * A warehouse controller is a **routable destination for fluid**, the same way it already was
	 * for items.
	 *
	 * It was not: the controller registered an item capability for its inbound buffer and no fluid
	 * one, so `FluidApi.BLOCK` found nothing at its position and the fluid router never weighed it.
	 * The gantry could put fluid away perfectly well - nothing could hand it any. This pins the
	 * routing decision itself rather than the exposure, since that is the symptom.
	 */
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testTheControllerIsARoutableFluidDestination() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val pipePos = BlockPos(1, 2, 0)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(pipePos, BlockRegistry.Pipe.defaultBlockState())

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))

		runAfterDelay(20) {
			val route = FluidPipeRouter.findRoute(level as ServerLevel, absolutePos(pipePos), FluidResource.of(Fluids.WATER))
			assertTrue(route?.lastOrNull() == absolutePos(controllerPos)) {
				"Expected water pushed onto the pipe to route into the warehouse controller, got $route"
			}
			succeed()
		}
	}

	/**
	 * ...and its filter card gates that decision for fluids exactly as it does for items.
	 *
	 * The buffer is what the router's simulated insert probes, so the filter has to live *in* the
	 * buffer: a controller told to reject water must not advertise itself as somewhere to send
	 * water. Without this the fluid buffer accepted everything and a filtered warehouse quietly took
	 * fluids it had been configured to refuse.
	 */
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testTheControllerFilterRejectsFluidItIsNotConfiguredFor() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val pipePos = BlockPos(1, 2, 0)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(pipePos, BlockRegistry.Pipe.defaultBlockState())

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))

		// A fluid card naming lava, on a whitelist controller - so water is not wanted here.
		val card = ItemStack(ItemRegistry.FluidFilterCard)
		FilterCardState(card).apply {
			(currentState() as FluidConditionState).fluidMatches[0] = FluidResource.of(Fluids.LAVA)
			touchCurrentState()
		}
		controller.filter[0].set(card)

		runAfterDelay(20) {
			val serverLevel = level as ServerLevel
			val lava = FluidPipeRouter.findRoute(serverLevel, absolutePos(pipePos), FluidResource.of(Fluids.LAVA))
			assertTrue(lava?.lastOrNull() == absolutePos(controllerPos)) {
				"Expected the whitelisted lava to still route into the controller, got $lava"
			}
			val water = FluidPipeRouter.findRoute(serverLevel, absolutePos(pipePos), FluidResource.of(Fluids.WATER))
			assertTrue(water?.lastOrNull() != absolutePos(controllerPos)) {
				"Expected water to be refused by the controller's own filter card, got $water"
			}
			succeed()
		}
	}
}
