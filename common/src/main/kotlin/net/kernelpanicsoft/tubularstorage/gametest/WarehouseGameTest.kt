package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.pipe.entity.FilterMode
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.RoutingModule
import net.kernelpanicsoft.tubularstorage.pipe.hook.ExtractionHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.RequesterHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.RequesterHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookType
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.kernelpanicsoft.tubularstorage.warehouse.Bounds
import net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity
import net.kernelpanicsoft.tubularstorage.warehouse.WarehouseIndex
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.phys.Vec3

/** GameTest coverage for [net.kernelpanicsoft.tubularstorage.warehouse.WarehouseWandItem]'s bind flow. */
@Suppress("unused")
class WarehouseGameTest {
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testWandBindsControllerToClickedCorners() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerOnePos = BlockPos(0, 2, 1)
		val cornerTwoPos = BlockPos(3, 4, 2)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())

		val player = makeMockPlayer(GameType.CREATIVE)
		player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(ItemRegistry.WarehouseWand))

		useBlock(cornerOnePos, player)
		useBlock(cornerTwoPos, player)
		useBlock(controllerPos, player)

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		val expected = Bounds.of(absolutePos(cornerOnePos), absolutePos(cornerTwoPos))
		assertTrue(controller.bounds == expected) {
			"Expected the controller to have bound $expected, got ${controller.bounds}"
		}

		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testWandRestartsSelectionAfterBinding() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerOnePos = BlockPos(0, 2, 1)
		val cornerTwoPos = BlockPos(1, 2, 1)
		val nextCornerPos = BlockPos(2, 2, 1)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())

		val player = makeMockPlayer(GameType.CREATIVE)
		player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(ItemRegistry.WarehouseWand))

		useBlock(cornerOnePos, player)
		useBlock(cornerTwoPos, player)
		useBlock(controllerPos, player)
		val boundAfterFirstBind = (getBlockEntity(controllerPos) as WarehouseControllerBlockEntity).bounds

		// A fourth click, with the wand's selection already cleared by the bind above, must start a
		// fresh selection rather than immediately rebinding the controller against stale corners.
		useBlock(nextCornerPos, player)
		useBlock(controllerPos, player)
		val boundAfterStrayClick = (getBlockEntity(controllerPos) as WarehouseControllerBlockEntity).bounds

		assertTrue(boundAfterStrayClick == boundAfterFirstBind) {
			"Expected a single stray click after binding to leave the controller's bounds ($boundAfterFirstBind) unchanged, got $boundAfterStrayClick"
		}

		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testControllerIndexesRacksInsideBoundsOnly() {
		val controllerPos = BlockPos(0, 2, 0)
		val insideChestPos = BlockPos(1, 2, 0)
		val outsideChestPos = BlockPos(3, 2, 3)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(insideChestPos, Blocks.CHEST.defaultBlockState())
		setBlock(outsideChestPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(insideChestPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 5))
		(getBlockEntity(outsideChestPos) as ChestBlockEntity).setItem(0, ItemStack(Items.GOLD_INGOT, 3))

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(insideChestPos))

		succeedWhen {
			val diamondEntries = controller.index.locations[ItemResource.of(ItemStack(Items.DIAMOND))]
			assertTrue(diamondEntries != null && diamondEntries.size == 1 && diamondEntries[0].amount == 5L) {
				"Expected one indexed diamond entry with amount 5, got $diamondEntries"
			}
			assertTrue(controller.index.locations[ItemResource.of(ItemStack(Items.GOLD_INGOT))] == null) {
				"Expected the gold ingot outside the bound volume to not be indexed"
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testGantryReachesMoveToTarget() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerOnePos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val targetPos = BlockPos(3, 2, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(cornerOnePos), absolutePos(cornerTwoPos))
		controller.moveGantryTo(absolutePos(targetPos))

		succeedWhen {
			assertTrue(!controller.gantry.isMoving) { "Expected the gantry to have finished its move by now" }
			val expected = Vec3.atCenterOf(absolutePos(targetPos))
			assertTrue(controller.gantry.pos.distanceTo(expected) < 0.01) {
				"Expected the gantry to have arrived at $expected, got ${controller.gantry.pos}"
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testStagingBufferContentsGetPutAway() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val rackPos = BlockPos(3, 2, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(rackPos, Blocks.CHEST.defaultBlockState())

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))
		controller.stagingBuffer.insert(ItemResource.of(ItemStack(Items.DIAMOND)), 5, false)

		succeedWhen {
			val rack = getBlockEntity(rackPos) as ChestBlockEntity
			assertTrue(rack.getItem(0).`is`(Items.DIAMOND) && rack.getItem(0).count == 5) {
				"Expected 5 diamonds to have been stowed into the rack, got ${rack.getItem(0)}"
			}
			assertTrue(controller.stagingBuffer.getAmount(0) == 0L) {
				"Expected the staging buffer to be empty after stowing, got amount ${controller.stagingBuffer.getAmount(0)}"
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testEnqueuedRetrieveDeliversToStagingBuffer() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val rackPos = BlockPos(3, 2, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 5))

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))
		val resource = ItemResource.of(ItemStack(Items.DIAMOND))
		val slot = WarehouseIndex.RackSlotRef(absolutePos(rackPos), null, 5)
		controller.enqueueRetrieve(slot, resource, 5)

		succeedWhen {
			assertTrue(controller.stagingBuffer.getAmount(0) == 5L && controller.stagingBuffer.getResource(0) == resource) {
				"Expected 5 diamonds to have been retrieved into the staging buffer, got amount ${controller.stagingBuffer.getAmount(0)} resource ${controller.stagingBuffer.getResource(0)}"
			}
			val rack = getBlockEntity(rackPos) as ChestBlockEntity
			assertTrue(rack.getItem(0).isEmpty) { "Expected the rack to be emptied by the retrieval, got ${rack.getItem(0)}" }
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testRequesterHookPullsFromWarehouse() {
		val rackPos = BlockPos(0, 2, 0)
		val controllerPos = BlockPos(1, 2, 0)
		val pipePos = BlockPos(2, 2, 0)
		val requesterHookPos = BlockPos(3, 2, 0)
		val destPos = BlockPos(3, 2, 1)
		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(pipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(requesterHookPos, BlockRegistry.Hook.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))

		val requester = getBlockEntity(requesterHookPos) as HookBlockEntity
		requester.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val requesterState = requester.hooks.getOrPut(Direction.SOUTH.name) { RequesterHookType.createState() } as RequesterHookState
		requesterState.request.insert(ItemResource.of(ItemStack(Items.DIAMOND)), 4, false)

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.DIAMOND) && dest.getItem(0).count == 4) {
				"Expected 4 diamonds pulled from the warehouse to have arrived, got ${dest.getItem(0)}"
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testWarehouseCanClaimTheDefaultRoute() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val defaultHookPos = BlockPos(0, 2, 2)
		val controllerPos = BlockPos(0, 2, 3)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Hook.defaultBlockState())
		setBlock(defaultHookPos, BlockRegistry.Hook.defaultBlockState())
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val extractor = getBlockEntity(extractorPos) as HookBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }

		// The warehouse claims the network's default route the same way any other destination
		// would - a sorting hook on the pipe facing it, at the reserved sentinel priority. No
		// warehouse-specific mechanism needed.
		val defaultHook = getBlockEntity(defaultHookPos) as HookBlockEntity
		defaultHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val defaultState = defaultHook.hooks.getOrPut(Direction.SOUTH.name) { SortingHookType.createState() } as SortingHookState
		defaultState.routing = RoutingModule(mode = FilterMode.BLACKLIST, priority = RoutingModule.DEFAULT_ROUTE_PRIORITY)

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		succeedWhen {
			assertTrue(controller.stagingBuffer.getAmount(0) == 8L) {
				"Expected 8 diamonds with nowhere else to go to have landed in the warehouse claiming the default route, got amount ${controller.stagingBuffer.getAmount(0)}"
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testStaleIndexEntryDroppedAfterRackDepletedByHand() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val rackPos = BlockPos(3, 2, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 5))

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))
		val resource = ItemResource.of(ItemStack(Items.DIAMOND))

		// Wait for the initial rescan to actually index the rack (rather than constructing a
		// RackSlotRef by hand), then simulate a player emptying it directly - exactly the drift the
		// design's own low-frequency audit rescan exists to correct, but that's minutes away. A
		// second request against the now-stale entry shouldn't have to wait for it, or it'll keep
		// re-discovering the same dead slot and sending the gantry back and forth forever.
		runAfterDelay(10) {
			val indexed = controller.index.locations[resource]?.firstOrNull()
			assertTrue(indexed != null && indexed.amount == 5L) {
				"Expected the initial rescan to have indexed 5 diamonds, got $indexed"
			}
			(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack.EMPTY)
			controller.enqueueRetrieve(indexed!!, resource, 5)

			runAfterDelay(100) {
				assertTrue(controller.stagingBuffer.getAmount(0) == 0L) {
					"Expected nothing to have been retrieved from the now-empty rack, got amount ${controller.stagingBuffer.getAmount(0)}"
				}
				assertTrue(controller.index.locations[resource].isNullOrEmpty()) {
					"Expected the stale index entry to have been dropped after the failed retrieval, got ${controller.index.locations[resource]}"
				}
				assertTrue(!controller.gantry.isMoving) { "Expected the gantry to have finished its trip home by now" }
				succeed()
			}
		}
	}
}
