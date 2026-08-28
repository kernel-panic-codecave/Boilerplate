package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.warehouse.Bounds
import net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity
import net.kernelpanicsoft.tubularstorage.warehouse.WarehouseIndex
import net.kernelpanicsoft.tubularstorage.warehouse.rack.BulkRackBlockEntity
import net.kernelpanicsoft.tubularstorage.warehouse.rack.GeneralRackBlockEntity
import net.kernelpanicsoft.tubularstorage.warehouse.rack.UnstackableRackBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * GameTest coverage for the three built-in rack block families
 * ([net.kernelpanicsoft.tubularstorage.warehouse.rack.GeneralRackBlockEntity]/
 * [net.kernelpanicsoft.tubularstorage.warehouse.rack.BulkRackBlockEntity]/
 * [net.kernelpanicsoft.tubularstorage.warehouse.rack.UnstackableRackBlockEntity]). The first batch
 * below exercises each rack's own storage behavior directly through [ItemApi.BLOCK.find] (the same
 * lookup [net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity]'s own gantry
 * uses) rather than a bound warehouse; the later `RoundTrip`/`OnInitialScan` tests instead bind a
 * real controller, confirming each rack type is indexed and put-away/retrieved through *without*
 * needing any special-casing in [net.kernelpanicsoft.tubularstorage.warehouse.WarehouseIndex] -
 * exactly the "any block exposing `ItemApi.BLOCK` is automatically a rack" principle this whole
 * system is built on (`docs/design/m3-warehouse-storage.md`).
 */
@Suppress("unused")
class RackGameTest {
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testGeneralRackStoresMultipleDistinctItemsLikeAChest() {
		val rackPos = BlockPos(0, 2, 0)
		setBlock(rackPos, BlockRegistry.GeneralRack.defaultBlockState())
		val storage = ItemApi.BLOCK.find(level, absolutePos(rackPos), null)!!

		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))
		val gold = ItemResource.of(ItemStack(Items.GOLD_INGOT))
		assertTrue(storage.insert(diamond, 5, false) == 5L) { "Expected 5 diamonds to insert into the general rack" }
		assertTrue(storage.insert(gold, 3, false) == 3L) { "Expected 3 gold ingots to also insert, into a separate slot" }

		assertTrue(storage.extract(diamond, 5, false) == 5L) { "Expected 5 diamonds to extract back out" }
		assertTrue(storage.extract(gold, 3, false) == 3L) { "Expected 3 gold ingots to extract back out" }

		val tile = getBlockEntity(rackPos) as GeneralRackBlockEntity
		assertTrue((0 until tile.storage.size()).all { tile.storage.get(it).resource.isBlank }) {
			"Expected the general rack to be empty again after extracting everything"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testBulkRackHoldsFarMoreThanVanillaStackSize() {
		val rackPos = BlockPos(0, 2, 0)
		setBlock(rackPos, BlockRegistry.BulkRack.defaultBlockState())
		val storage = ItemApi.BLOCK.find(level, absolutePos(rackPos), null)!!

		val cobblestone = ItemResource.of(ItemStack(Items.COBBLESTONE))
		val inserted = storage.insert(cobblestone, 5000, false)
		assertTrue(inserted == 5000L) { "Expected all 5000 cobblestone to fit in one bulk rack slot, got $inserted" }

		val extracted = storage.extract(cobblestone, 5000, false)
		assertTrue(extracted == 5000L) { "Expected all 5000 cobblestone back out, got $extracted" }
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testBulkRackRejectsASecondResourceUntilEmptied() {
		val rackPos = BlockPos(0, 2, 0)
		setBlock(rackPos, BlockRegistry.BulkRack.defaultBlockState())
		val storage = ItemApi.BLOCK.find(level, absolutePos(rackPos), null)!!

		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))
		val gold = ItemResource.of(ItemStack(Items.GOLD_INGOT))
		storage.insert(diamond, 64, false)

		assertTrue(storage.insert(gold, 1, false) == 0L) { "Expected a bulk rack already holding diamonds to reject gold" }

		storage.extract(diamond, 64, false)
		assertTrue(storage.insert(gold, 1, false) == 1L) { "Expected an emptied bulk rack to accept a different resource" }
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testUnstackableRackDedupesIdenticalComponentsIntoOneRecord() {
		val rackPos = BlockPos(0, 2, 0)
		setBlock(rackPos, BlockRegistry.UnstackableRack.defaultBlockState())
		val storage = ItemApi.BLOCK.find(level, absolutePos(rackPos), null)!!

		val namedPickaxe = ItemStack(Items.DIAMOND_PICKAXE).apply { set(DataComponents.CUSTOM_NAME, Component.literal("Special")) }
		val resource = ItemResource.of(namedPickaxe)

		storage.insert(resource, 1, false)
		storage.insert(resource, 1, false)

		val tile = getBlockEntity(rackPos) as UnstackableRackBlockEntity
		val usedSlots = (0 until tile.storage.size()).count { !tile.storage.get(it).resource.isBlank }
		assertTrue(usedSlots == 1) { "Expected two drops with identical components to dedupe into one record, got $usedSlots used slots" }
		assertTrue(storage.extract(resource, 2, false) == 2L) { "Expected the one deduped record to hold a count of 2" }
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testUnstackableRackGivesDistinctComponentsSeparateRecords() {
		val rackPos = BlockPos(0, 2, 0)
		setBlock(rackPos, BlockRegistry.UnstackableRack.defaultBlockState())
		val storage = ItemApi.BLOCK.find(level, absolutePos(rackPos), null)!!

		val pickaxeA = ItemResource.of(ItemStack(Items.DIAMOND_PICKAXE).apply { set(DataComponents.CUSTOM_NAME, Component.literal("A")) })
		val pickaxeB = ItemResource.of(ItemStack(Items.DIAMOND_PICKAXE).apply { set(DataComponents.CUSTOM_NAME, Component.literal("B")) })

		storage.insert(pickaxeA, 1, false)
		storage.insert(pickaxeB, 1, false)

		val tile = getBlockEntity(rackPos) as UnstackableRackBlockEntity
		val usedSlots = (0 until tile.storage.size()).count { !tile.storage.get(it).resource.isBlank }
		assertTrue(usedSlots == 2) { "Expected two distinct component sets to occupy separate records, got $usedSlots used slots" }
		succeed()
	}

	/**
	 * Confirms [net.kernelpanicsoft.tubularstorage.warehouse.WarehouseIndex.scanPosition] discovers
	 * pre-existing contents in all three rack types on the initial bind scan, the same way it
	 * already does for a vanilla chest ([WarehouseGameTest.testControllerIndexesRacksInsideBoundsOnly]) -
	 * no rack-type-specific code path exists to have this, so this is really a check that
	 * [net.kernelpanicsoft.tubularstorage.warehouse.rack.exposeRackStorage] actually wires
	 * [BulkRackBlockEntity]/[UnstackableRackBlockEntity] into [ItemApi.BLOCK] correctly (
	 * [GeneralRackBlockEntity] uses Archie's own `exposeItemStorage` and was never really in doubt).
	 */
	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testWarehouseIndexesAllThreeRackTypesOnInitialScan() {
		val controllerPos = BlockPos(0, 2, 0)
		val generalPos = BlockPos(1, 2, 0)
		val bulkPos = BlockPos(2, 2, 0)
		val unstackablePos = BlockPos(3, 2, 0)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(generalPos, BlockRegistry.GeneralRack.defaultBlockState())
		setBlock(bulkPos, BlockRegistry.BulkRack.defaultBlockState())
		setBlock(unstackablePos, BlockRegistry.UnstackableRack.defaultBlockState())

		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))
		val cobblestone = ItemResource.of(ItemStack(Items.COBBLESTONE))
		val namedPickaxe = ItemResource.of(ItemStack(Items.DIAMOND_PICKAXE).apply { set(DataComponents.CUSTOM_NAME, Component.literal("Special")) })
		ItemApi.BLOCK.find(level, absolutePos(generalPos), null)!!.insert(diamond, 5, false)
		ItemApi.BLOCK.find(level, absolutePos(bulkPos), null)!!.insert(cobblestone, 500, false)
		ItemApi.BLOCK.find(level, absolutePos(unstackablePos), null)!!.insert(namedPickaxe, 1, false)

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(unstackablePos))

		succeedWhen {
			val diamondEntries = controller.index.locations[diamond]
			assertTrue(diamondEntries != null && diamondEntries.size == 1 && diamondEntries[0].amount == 5L) {
				"Expected the general rack's 5 diamonds to be indexed, got $diamondEntries"
			}
			val cobblestoneEntries = controller.index.locations[cobblestone]
			assertTrue(cobblestoneEntries != null && cobblestoneEntries.size == 1 && cobblestoneEntries[0].amount == 500L) {
				"Expected the bulk rack's 500 cobblestone to be indexed, got $cobblestoneEntries"
			}
			val pickaxeEntries = controller.index.locations[namedPickaxe]
			assertTrue(pickaxeEntries != null && pickaxeEntries.size == 1 && pickaxeEntries[0].amount == 1L) {
				"Expected the unstackable rack's named pickaxe to be indexed, got $pickaxeEntries"
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testGeneralRackRoundTripsThroughGantry() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val rackPos = BlockPos(3, 2, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(rackPos, BlockRegistry.GeneralRack.defaultBlockState())

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))
		val resource = ItemResource.of(ItemStack(Items.DIAMOND))
		controller.inboundBuffer.insert(resource, 5, false)

		var stowed = false

		succeedWhen {
			if (!stowed) {
				val storage = ItemApi.BLOCK.find(level, absolutePos(rackPos), null)!!
				if (storage.getAmount(0) == 5L) {
					stowed = true
					controller.enqueueRetrieve(
						WarehouseIndex.RackSlotRef(absolutePos(rackPos), null, 5),
						ResourceStack(resource, 5),
					)
				}
			}
			assertTrue(stowed) { "Expected the 5 diamonds to have been stowed into the general rack by now" }

			assertTrue(controller.outboundBuffer.getAmount(0) == 5L && controller.outboundBuffer.getResource(0) == resource) {
				"Expected the 5 diamonds to have round-tripped back out into the outbound buffer, got amount ${controller.outboundBuffer.getAmount(0)}"
			}
			val storage = ItemApi.BLOCK.find(level, absolutePos(rackPos), null)!!
			assertTrue(storage.getAmount(0) == 0L) { "Expected the general rack to be empty again after the retrieval, got amount ${storage.getAmount(0)}" }
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testBulkRackRoundTripsThroughGantry() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val rackPos = BlockPos(3, 2, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(rackPos, BlockRegistry.BulkRack.defaultBlockState())

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))
		// The staging buffer's own slots are still vanilla-capped (see ArchieItemSlot) regardless of
		// the bulk rack's own huge per-slot limit - a round trip through it can only move up to a
		// vanilla stack per job either way, so 64 (not some huge number) is the right amount here.
		val resource = ItemResource.of(ItemStack(Items.COBBLESTONE))
		controller.inboundBuffer.insert(resource, 64, false)

		var stowed = false

		succeedWhen {
			if (!stowed) {
				val storage = ItemApi.BLOCK.find(level, absolutePos(rackPos), null)!!
				if (storage.getAmount(0) == 64L) {
					stowed = true
					controller.enqueueRetrieve(
						WarehouseIndex.RackSlotRef(absolutePos(rackPos), null, 64),
						ResourceStack(resource, 64),
					)
				}
			}
			assertTrue(stowed) { "Expected the 64 cobblestone to have been stowed into the bulk rack by now" }

			assertTrue(controller.outboundBuffer.getAmount(0) == 64L && controller.outboundBuffer.getResource(0) == resource) {
				"Expected the 64 cobblestone to have round-tripped back out into the outbound buffer, got amount ${controller.outboundBuffer.getAmount(0)}"
			}
			val storage = ItemApi.BLOCK.find(level, absolutePos(rackPos), null)!!
			assertTrue(storage.getAmount(0) == 0L) { "Expected the bulk rack to be empty again after the retrieval, got amount ${storage.getAmount(0)}" }
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testUnstackableRackRoundTripsThroughGantry() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val rackPos = BlockPos(3, 2, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(rackPos, BlockRegistry.UnstackableRack.defaultBlockState())

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))
		val resource = ItemResource.of(ItemStack(Items.DIAMOND_PICKAXE).apply { set(DataComponents.CUSTOM_NAME, Component.literal("Special")) })
		controller.inboundBuffer.insert(resource, 1, false)

		var stowed = false

		succeedWhen {
			if (!stowed) {
				val storage = ItemApi.BLOCK.find(level, absolutePos(rackPos), null)!!
				if (storage.getAmount(0) == 1L) {
					stowed = true
					controller.enqueueRetrieve(
						WarehouseIndex.RackSlotRef(absolutePos(rackPos), null, 1),
						ResourceStack(resource, 1),
					)
				}
			}
			assertTrue(stowed) { "Expected the named pickaxe to have been stowed into the unstackable rack by now" }

			assertTrue(controller.outboundBuffer.getAmount(0) == 1L && controller.outboundBuffer.getResource(0) == resource) {
				"Expected the named pickaxe to have round-tripped back out into the outbound buffer, got amount ${controller.outboundBuffer.getAmount(0)}"
			}
			val storage = ItemApi.BLOCK.find(level, absolutePos(rackPos), null)!!
			assertTrue(storage.getAmount(0) == 0L) { "Expected the unstackable rack to be empty again after the retrieval, got amount ${storage.getAmount(0)}" }
		}
	}
}
