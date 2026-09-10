package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.fluid.util.FluidAmounts
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.archie.gui.blockentity.BlockEntityStateContainer
import net.kernelpanicsoft.archie.gui.blockentity.getStateContainer
import net.kernelpanicsoft.archie.transfer.ArchieFluidStorage
import net.kernelpanicsoft.boilerplate.resource.ResourceIdentity
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.ResourceConditionState
import net.kernelpanicsoft.boilerplate.pipe.gui.reachableStock
import net.kernelpanicsoft.boilerplate.pipe.network.FluidPipeRouter
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.warehouse.*
import net.kernelpanicsoft.boilerplate.warehouse.tank.FluidTankBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.Fluids
import net.kernelpanicsoft.boilerplate.warehouse.entity.WarehouseControllerBlockEntity

/**
 * The warehouse is **resource-kind agnostic**: a tank inside a bound volume is an ordinary rack, and
 * the gantry moves fluid out of and into it exactly as it moves items.
 *
 * Nothing in the warehouse names items or fluids any more - it asks the registry which kinds have a
 * [net.kernelpanicsoft.boilerplate.resource.ResourceStorageKind] and drives them all the same way.
 * These tests pin that from the outside: they only ever use the public warehouse API, so they would
 * pass unchanged for a third kind an addon registers.
 */
@Suppress("unused")
class WarehouseFluidGameTest {
	private val bucket: Long get() = FluidAmounts.toPlatformAmount(1000L)

	private val water: FluidResource get() = FluidResource.of(Fluids.WATER)

	/**
	 * A tank reports a real capacity and its own contents - what `FluidTankScreen` draws.
	 *
	 * The capacity assertion is the point. Every `FluidAmounts` constant reads `0` in Common Storage
	 * Lib 0.0.5, so a capacity taken from one would be zero - the screen would draw an empty gauge
	 * over a full tank, and the tank itself would silently accept nothing. `capacity` converts
	 * through `toPlatformAmount` instead, and this is what catches a regression back to a constant.
	 *
	 * The menu is not built here: `ComposeBlockContainerMenu` casts the opening player to a
	 * `ServerPlayer` and a gametest's mock player is not one. It is a pass-through over exactly the
	 * two values asserted below. That the tank is now `@Sync`'d - which the doc had recorded as
	 * impossible - is pinned by every other fluid test here still passing with sync switched on.
	 */
	/**
	 * The tank is obtainable.
	 *
	 * It was not: the block, its block entity, its blockstate, models, loot table, lang entry and
	 * mineable tag all existed, but no `BlockItem` was ever registered - so the block could not be
	 * picked up, given, or placed by a player, and its loot table dropped an item that did not exist.
	 * Datagen had even emitted an item model for it, which is what makes the omission easy to miss.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testTheTankHasABlockItem() {
		val item = BlockRegistry.FluidTank.asItem()
		assertTrue(item != Items.AIR) { "The Fluid Tank block has no item, so nothing can obtain or place it" }
		assertTrue(item == ItemRegistry.FluidTank) { "Expected the tank's item to be its registered BlockItem, got $item" }
		succeed()
	}

	/**
	 * What a client receives for a tank actually contains the fluid.
	 *
	 * Two separate faults made a full tank draw as empty, and this covers both. Archie's `getSyncTag`
	 * read its serialized `data` map without flushing the live storages into it first, so the tag
	 * carried whatever was last written to disk - empty, for a tank filled at runtime. And `@Sync`
	 * only decides what a *sent* tag carries; nothing asked for one to be sent, so the contents were
	 * frozen at chunk-load even once the tag was right.
	 *
	 * Asserted by round-tripping through a second block entity, which is exactly what the client does
	 * with the packet: a tag that merely exists proves nothing if loading it yields an empty tank.
	 */
	/**
	 * A viewer who starts watching a tank that was already full is told what it holds.
	 *
	 * Archie's block-entity sync is a delta stream: a container only recorded a property when
	 * something *changed* it, and only ever sent properties marked dirty. So a tank filled before
	 * anyone opened its screen - or simply loaded from disk - had no recorded value and nothing to
	 * send, and the screen sat empty until the tank next happened to change. Starting to track now
	 * seeds from the live block entity and marks everything dirty, so the first packet is a snapshot.
	 */
	/**
	 * Filling a tank someone is already watching marks it for sync.
	 *
	 * The container recorded a property's value and skipped the update when it compared equal to what
	 * was already there - but a storage-backed property hands back the *same* mutable object every
	 * time, so that comparison was an object against itself and never reported a change. Nothing was
	 * ever marked dirty, no packet was ever sent, and an open screen froze at whatever it had when it
	 * opened. Reopening appeared to fix it only because opening seeds and force-marks the state.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testFillingAWatchedTankMarksItDirty() {
		val tankPos = BlockPos(1, 2, 1)
		setBlock(tankPos, BlockRegistry.FluidTank.defaultBlockState())
		val tank = getBlockEntity(tankPos) as FluidTankBlockEntity

		// The container the field itself publishes into - same instance, via getStateContainer().
		val container = tank.getStateContainer()
		container.captureCurrentValues()
		container.clearDirty(0)
		assertTrue(container.getDirtyProperties().isEmpty()) { "Expected a freshly synced container to be clean" }

		tank.storage.insert(water, bucket, false)

		assertTrue("storage" in container.getDirtyProperties()) {
			"Filling the tank left the container clean, so no packet would ever be sent - got ${container.getDirtyProperties()}"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testAFreshViewerIsSentTheCurrentContents() {
		val tankPos = BlockPos(1, 2, 1)
		setBlock(tankPos, BlockRegistry.FluidTank.defaultBlockState())
		val tank = getBlockEntity<FluidTankBlockEntity>(tankPos)
		tank.storage.insert(water, bucket * 3, false)

		// A container built after the fact, exactly as one is when a screen opens on an idle tank.
		val container = BlockEntityStateContainer(tank)
		assertTrue(container.getProperty("storage") == null) { "A fresh container should start with nothing recorded" }

		container.captureCurrentValues()
		container.markAllDirty()

		assertTrue("storage" in container.getDirtyProperties()) {
			"Expected the seeded storage to be dirty so the first packet carries it, got ${container.getDirtyProperties()}"
		}
		val seeded = container.getProperty("storage") as? ArchieFluidStorage
		assertTrue(seeded != null && seeded.getAmount(0) == bucket * 3) {
			"Expected the seed to carry the 3 buckets the tank holds, got ${(container.getProperty("storage") as? ArchieFluidStorage)?.getAmount(0)}"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testATanksUpdateTagCarriesItsContents() {
		val tankPos = BlockPos(1, 2, 1)
		setBlock(tankPos, BlockRegistry.FluidTank.defaultBlockState())
		val tank = getBlockEntity(tankPos) as FluidTankBlockEntity
		tank.storage.insert(water, bucket * 2, false)

		val tag = tank.getUpdateTag(level.registryAccess())
		assertTrue(!tag.isEmpty) { "A synced tank sent an empty update tag" }

		val received = FluidTankBlockEntity(absolutePos(tankPos), BlockRegistry.FluidTank.defaultBlockState())
		received.loadFromTag(tag)
		val stored = received.storage.get(0)
		assertTrue(ResourceIdentity.of(stored.resource) == ResourceIdentity.of(water)) {
			"A client loading the update tag saw ${stored.resource}, not the water the tank holds"
		}
		assertTrue(stored.amount == bucket * 2) {
			"A client loading the update tag saw ${stored.amount}, not the 2 buckets the tank holds"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testATankReportsARealCapacityAndItsContents() {
		val tankPos = BlockPos(1, 2, 1)
		setBlock(tankPos, BlockRegistry.FluidTank.defaultBlockState())
		val tank = getBlockEntity(tankPos) as FluidTankBlockEntity

		assertTrue(FluidTankBlockEntity.capacity > 0) {
			"Expected a real capacity, got ${FluidTankBlockEntity.capacity} - a FluidAmounts constant has crept back in"
		}

		tank.storage.insert(water, bucket * 2, false)
		val stored = tank.storage.get(0)
		assertTrue(ResourceIdentity.of(stored.resource) == ResourceIdentity.of(water)) {
			"Expected the tank to hold water, got ${stored.resource}"
		}
		assertTrue(stored.amount == bucket * 2) { "Expected 2 buckets, got ${stored.amount}" }
		succeed()
	}

	/** A controller with a bound volume, plus a tank inside it - the smallest fluid warehouse. */
	/**
	 * A terminal reachable from a warehouse holding fluid **lists that fluid**.
	 *
	 * The whole point of indexing a tank: it was routable, withdrawable and defraggable the entire
	 * time, and simply never appeared in the terminal's own list - so nothing could ever be asked
	 * for. The listing narrowed itself to the item kind long after the grid had gained the ability
	 * to draw any of them, and nothing caught it because the aggregation was buried in a method that
	 * needed a menu and a player to call. [reachableStock] is that aggregation, liftable out and
	 * asked directly.
	 */
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testATerminalListsFluidHeldInAReachableWarehouse() {
		val level = level as ServerLevel
		val tankPos = BlockPos(3, 2, 4)
		val pipePos = BlockPos(1, 2, 0)
		layOutFluidWarehouse(tankPos, fill = bucket * 3)
		setBlock(pipePos, BlockRegistry.Pipe.defaultBlockState())

		succeedWhen {
			val listed = reachableStock(level, absolutePos(pipePos))
			val waterEntry = listed.firstOrNull { ResourceIdentity.of(it.resource) == ResourceIdentity.of(water) }
			assertTrue(waterEntry != null) {
				"Expected the warehouse's own water to be listed at a reachable terminal, got ${listed.map { it.resource }}"
			}
			assertTrue(waterEntry!!.amount == bucket * 3) {
				"Expected all three buckets to be listed as one entry, got ${waterEntry.amount}"
			}
		}
	}

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
	 * A bound (but rackless) warehouse with a pipe run up against it - the fixture for asking
	 * whether the *controller itself* is a routable destination, which has nothing to do with what
	 * it contains. Returns `(controller position, pipe position)`, both relative.
	 */
	private fun GameTestHelper.layOutPipedWarehouse(): Pair<BlockPos, BlockPos> {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val pipePos = BlockPos(1, 2, 0)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(pipePos, BlockRegistry.Pipe.defaultBlockState())

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))
		return controllerPos to pipePos
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
			val outbound = controller.outboundFor<FluidResource>(ResourceKindRegistry.Fluid)
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

		val inbound = controller.inboundFor<FluidResource>(ResourceKindRegistry.Fluid)
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
		val (controllerPos, pipePos) = layOutPipedWarehouse()

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
		val (controllerPos, pipePos) = layOutPipedWarehouse()
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity

		// A fluid card naming lava, on a whitelist controller - so water is not wanted here.
		val card = ItemStack(ItemRegistry.ResourceFilterCard)
		FilterCardState(card).apply {
			(currentState() as ResourceConditionState).resourceMatches[0] = FluidResource.of(Fluids.LAVA)
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
