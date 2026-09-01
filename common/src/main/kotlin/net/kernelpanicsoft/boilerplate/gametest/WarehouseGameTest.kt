package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.FilterHookType
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.warehouse.Bounds
import net.kernelpanicsoft.boilerplate.warehouse.DeliveryTarget
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseIndex
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseScale
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

/** GameTest coverage for [net.kernelpanicsoft.boilerplate.warehouse.WarehouseWandItem]'s bind flow. */
@Suppress("unused")
class WarehouseGameTest {
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testWandBindsControllerToClickedCorners() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerOnePos = BlockPos(0, 2, 1)
		val cornerTwoPos = BlockPos(3, 4, 2)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())

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
		placeAdjacentPressureSource(controllerPos.below())

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
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(insideChestPos, Blocks.CHEST.defaultBlockState())
		setBlock(outsideChestPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(insideChestPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 5))
		(getBlockEntity(outsideChestPos) as ChestBlockEntity).setItem(0, ItemStack(Items.GOLD_INGOT, 3))

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(insideChestPos))

		succeedWhen {
			val diamondEntries = controller.index.slotsFor(ItemResource.of(ItemStack(Items.DIAMOND)))
			assertTrue(diamondEntries.size == 1 && diamondEntries[0].amount == 5L) {
				"Expected one indexed diamond entry with amount 5, got $diamondEntries"
			}
			assertTrue(controller.index.slotsFor(ItemResource.of(ItemStack(Items.GOLD_INGOT))).isEmpty()) {
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
		placeAdjacentPressureSource(controllerPos.below())

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
	fun GameTestHelper.testInboundBufferContentsGetPutAway() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val rackPos = BlockPos(3, 2, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(rackPos, Blocks.CHEST.defaultBlockState())

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))
		controller.inboundBuffer.insert(ItemResource.of(ItemStack(Items.DIAMOND)), 5, false)

		succeedWhen {
			val rack = getBlockEntity(rackPos) as ChestBlockEntity
			assertTrue(rack.getItem(0).`is`(Items.DIAMOND) && rack.getItem(0).count == 5) {
				"Expected 5 diamonds to have been stowed into the rack, got ${rack.getItem(0)}"
			}
			assertTrue(controller.inboundBuffer.getAmount(0) == 0L) {
				"Expected the inbound buffer to be empty after stowing, got amount ${controller.inboundBuffer.getAmount(0)}"
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testEnqueuedRetrieveDeliversToOutboundBuffer() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val rackPos = BlockPos(3, 2, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 5))

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))
		val resource = ItemResource.of(ItemStack(Items.DIAMOND))
		val slot = WarehouseIndex.RackSlotRef(absolutePos(rackPos), null, 5)
		controller.enqueueRetrieve(slot, ResourceStack(resource, 5))

		succeedWhen {
			assertTrue(controller.outboundBuffer.getAmount(0) == 5L && controller.outboundBuffer.getResource(0) == resource) {
				"Expected 5 diamonds to have been retrieved into the outbound buffer, got amount ${controller.outboundBuffer.getAmount(0)} resource ${controller.outboundBuffer.getResource(0)}"
			}
			val rack = getBlockEntity(rackPos) as ChestBlockEntity
			assertTrue(rack.getItem(0).isEmpty) { "Expected the rack to be emptied by the retrieval, got ${rack.getItem(0)}" }
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testGantryBatchesMultiplePickupsInOneTrip() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val rackOnePos = BlockPos(4, 2, 0)
		val rackTwoPos = BlockPos(4, 2, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(rackOnePos, Blocks.CHEST.defaultBlockState())
		setBlock(rackTwoPos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(rackOnePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 5))
		(getBlockEntity(rackTwoPos) as ChestBlockEntity).setItem(0, ItemStack(Items.GOLD_INGOT, 3))

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))
		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))
		val gold = ItemResource.of(ItemStack(Items.GOLD_INGOT))
		controller.enqueueRetrieve(WarehouseIndex.RackSlotRef(absolutePos(rackOnePos), null, 5), ResourceStack(diamond, 5))
		controller.enqueueRetrieve(WarehouseIndex.RackSlotRef(absolutePos(rackTwoPos), null, 3), ResourceStack(gold, 3))

		succeedWhen {
			val rackOne = getBlockEntity(rackOnePos) as ChestBlockEntity
			val rackTwo = getBlockEntity(rackTwoPos) as ChestBlockEntity
			assertTrue(rackOne.getItem(0).isEmpty && rackTwo.getItem(0).isEmpty) {
				"Expected both racks to have been emptied by the batched retrieval, got ${rackOne.getItem(0)} / ${rackTwo.getItem(0)}"
			}
			val outboundContents = (0 until 9).map { controller.outboundBuffer.getResource(it) to controller.outboundBuffer.getAmount(it) }
			assertTrue(outboundContents.any { it.first == diamond && it.second == 5L } && outboundContents.any { it.first == gold && it.second == 3L }) {
				"Expected 5 diamonds and 3 gold ingots to both have landed in the outbound buffer from one batched trip, got $outboundContents"
			}
			assertTrue(!controller.gantry.isMoving) { "Expected the gantry to have finished the batch and returned home by now" }
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
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(pipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(requesterHookPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))

		val requester = getBlockEntity(requesterHookPos) as MultipartBlockEntity
		requester.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val requesterState = requester.hooks.getOrPut(Direction.SOUTH.name) { RequesterHookType.createState() } as RequesterHookState
		requesterState.request.insert(ItemResource.of(ItemStack(Items.DIAMOND)), 4, false)
		placeCreativePressureSource(requesterHookPos.above())

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
		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(defaultHookPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		// The warehouse claims the network's default route the same way any other destination
		// would - a sorting hook on the pipe facing it, at the reserved sentinel priority. No
		// warehouse-specific mechanism needed.
		val defaultHook = getBlockEntity(defaultHookPos) as MultipartBlockEntity
		defaultHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val defaultState = defaultHook.hooks.getOrPut(Direction.SOUTH.name) { FilterHookType.createState() } as SortingHookState
		defaultState.routing = RoutingModule(mode = FilterMode.BLACKLIST, priority = RoutingModule.DEFAULT_ROUTE_PRIORITY)

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		succeedWhen {
			assertTrue(controller.inboundBuffer.getAmount(0) == 8L) {
				"Expected 8 diamonds with nowhere else to go to have landed in the warehouse claiming the default route, got amount ${controller.inboundBuffer.getAmount(0)}"
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testStaleIndexEntryDroppedAfterRackDepletedByHand() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val rackPos = BlockPos(3, 2, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
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
			val indexed = controller.index.slotsFor(resource).firstOrNull()
			assertTrue(indexed != null && indexed.amount == 5L) {
				"Expected the initial rescan to have indexed 5 diamonds, got $indexed"
			}
			(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack.EMPTY)
			controller.enqueueRetrieve(indexed!!, ResourceStack(resource, 5))

			runAfterDelay(100) {
				assertTrue(controller.outboundBuffer.getAmount(0) == 0L) {
					"Expected nothing to have been retrieved from the now-empty rack, got amount ${controller.outboundBuffer.getAmount(0)}"
				}
				assertTrue(controller.index.slotsFor(resource).isEmpty()) {
					"Expected the stale index entry to have been dropped after the failed retrieval, got ${controller.index.slotsFor(resource)}"
				}
				assertTrue(!controller.gantry.isMoving) { "Expected the gantry to have finished its trip home by now" }
				succeed()
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testBindingPlacesGantryRailFrame() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))

		val edgeFramePos = BlockPos(4, 3, 0)
		assertTrue(getBlockState(edgeFramePos).block == BlockRegistry.GantryRail) {
			"Expected a gantry rail frame block at $edgeFramePos after binding, got ${getBlockState(edgeFramePos)}"
		}
		val cornerFramePos = BlockPos(4, 3, 4)
		assertTrue(getBlockState(cornerFramePos).block == BlockRegistry.GantryRail) {
			"Expected a gantry rail frame block at the far corner $cornerFramePos after binding, got ${getBlockState(cornerFramePos)}"
		}
		val interiorPos = BlockPos(2, 3, 2)
		assertTrue(getBlockState(interiorPos).block != BlockRegistry.GantryRail) {
			"Expected the frame to trace only the footprint's border, not its interior at $interiorPos, got ${getBlockState(interiorPos)}"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testRebindingRemovesOldFrame() {
		val controllerPos = BlockPos(0, 2, 0)
		val firstCornerTwoPos = BlockPos(4, 3, 4)
		val secondCornerTwoPos = BlockPos(2, 3, 2)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(firstCornerTwoPos))
		val staleFramePos = BlockPos(4, 3, 4)
		assertTrue(getBlockState(staleFramePos).block == BlockRegistry.GantryRail) {
			"Expected the first bind to place a frame block at $staleFramePos, got ${getBlockState(staleFramePos)}"
		}

		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(secondCornerTwoPos))
		assertTrue(getBlockState(staleFramePos).block != BlockRegistry.GantryRail) {
			"Expected rebinding to a smaller volume to remove the old frame block at $staleFramePos, got ${getBlockState(staleFramePos)}"
		}
		val newFramePos = BlockPos(2, 3, 2)
		assertTrue(getBlockState(newFramePos).block == BlockRegistry.GantryRail) {
			"Expected the new bind to place a frame block at $newFramePos, got ${getBlockState(newFramePos)}"
		}
		succeed()
	}

	/**
	 * Frame cleanup on removal has to happen from [WarehouseControllerBlock.onRemove], not
	 * [WarehouseControllerBlockEntity]'s own `setRemoved` - that also fires on an ordinary chunk
	 * unload (every chunk, all the time, including the whole world unloading at shutdown), and
	 * `removeFrame`'s `level.getBlockState` calls on other positions can try to synchronously load
	 * a neighboring chunk that will never actually resolve during a mass unload, hanging the server
	 * forever (confirmed via a real thread dump: `Server thread` parked in
	 * `ChunkMap.processUnloads` → `LevelChunk.clearAllBlockEntities` → `setRemoved` →
	 * `removeFrame` → `Level.getChunk`'s `managedBlock`). This only proves the *positive* half
	 * (breaking the block still correctly clears the frame) - the deadlock itself needs an actual
	 * chunk unload/shutdown to reproduce, not practical to force from a gametest.
	 */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testBreakingControllerRemovesFrame() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))
		val framePos = BlockPos(4, 3, 4)
		assertTrue(getBlockState(framePos).block == BlockRegistry.GantryRail) {
			"Expected binding to place a frame block at $framePos, got ${getBlockState(framePos)}"
		}

		destroyBlock(controllerPos)
		assertTrue(getBlockState(framePos).block != BlockRegistry.GantryRail) {
			"Expected breaking the controller to remove the frame block at $framePos, got ${getBlockState(framePos)}"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testWandRejectsBindingOffBorder() {
		val controllerPos = BlockPos(2, 2, 2)
		val cornerOnePos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 2, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())

		val player = makeMockPlayer(GameType.CREATIVE)
		player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(ItemRegistry.WarehouseWand))

		useBlock(cornerOnePos, player)
		useBlock(cornerTwoPos, player)
		useBlock(controllerPos, player)

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		assertTrue(controller.bounds == null) {
			"Expected binding to be rejected since $controllerPos isn't on the bound footprint's border, got ${controller.bounds}"
		}
		succeed()
	}

	/**
	 * A retrieval whose [DeliveryTarget.Pipe] destination can't actually be reached - here, no pipe
	 * is even attached to the controller - shouldn't silently lose the item: it should stay in the
	 * outbound buffer, retrievable normally, rather than [WarehouseControllerBlockEntity]'s internals
	 * extracting it into the void.
	 */
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testRetrieveWithUnreachablePipeTargetKeepsItemInOutboundBuffer() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val rackPos = BlockPos(3, 2, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 5))

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))
		val resource = ItemResource.of(ItemStack(Items.DIAMOND))
		val slot = WarehouseIndex.RackSlotRef(absolutePos(rackPos), null, 5)

		controller.enqueueRetrieve(slot, ResourceStack(resource, 5), DeliveryTarget.Pipe(absolutePos(BlockPos(100, 100, 100))))

		succeedWhen {
			val rack = getBlockEntity(rackPos) as ChestBlockEntity
			assertTrue(rack.getItem(0).isEmpty) { "Expected the rack to have been emptied by the retrieval regardless, got ${rack.getItem(0)}" }
			assertTrue(controller.outboundBuffer.getAmount(0) == 5L && controller.outboundBuffer.getResource(0) == resource) {
				"Expected the 5 diamonds to still be sitting in the outbound buffer since no pipe could route to the target, got amount ${controller.outboundBuffer.getAmount(0)} resource ${controller.outboundBuffer.getResource(0)}"
			}
			assertTrue(!controller.gantry.isMoving) { "Expected the gantry to have finished its trip home by now" }
		}
	}

	/**
	 * `bestRackFor` used to trust an already-indexed entry blindly, without re-checking it still had
	 * room - here, [fullRackPos] is indexed as already holding the resource (as if scanned before it
	 * filled up), but is actually completely full. The old bug: every `planPutAway` pass kept picking
	 * it anyway, the resulting `Stow` failed, the leftover went straight back into `inboundBuffer`,
	 * and the very next pass repeated the exact same failing choice - the gantry cycling an item in
	 * and out of the buffer forever instead of ever reaching [emptyRackPos], which has room.
	 */
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testPutAwaySkipsFullIndexedRackForOneWithRoom() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val fullRackPos = BlockPos(1, 2, 0)
		val emptyRackPos = BlockPos(4, 2, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(fullRackPos, Blocks.CHEST.defaultBlockState())
		setBlock(emptyRackPos, Blocks.CHEST.defaultBlockState())

		val fullRack = getBlockEntity(fullRackPos) as ChestBlockEntity
		for (slot in 0 until 27) fullRack.setItem(slot, ItemStack(Items.DIAMOND, 64))

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))
		val resource = ItemResource.of(ItemStack(Items.DIAMOND))
		controller.index.recordInsertion(resource, absolutePos(fullRackPos), null, 27L * 64L)
		controller.inboundBuffer.insert(resource, 5, false)

		succeedWhen {
			val emptyRack = getBlockEntity(emptyRackPos) as ChestBlockEntity
			assertTrue(emptyRack.getItem(0).`is`(Items.DIAMOND) && emptyRack.getItem(0).count == 5) {
				"Expected the 5 diamonds to have been stowed into the rack with room instead of endlessly retrying the full one, got ${emptyRack.getItem(0)}"
			}
			assertTrue(controller.inboundBuffer.getAmount(0) == 0L) {
				"Expected the inbound buffer to be empty, not stuck cycling the item back in, got amount ${controller.inboundBuffer.getAmount(0)}"
			}
			assertTrue(!controller.gantry.isMoving) { "Expected the gantry to have finished and returned home by now" }
		}
	}

	/**
	 * When choosing an *empty* rack (no existing indexed entry to stack with), `bestRackFor` should
	 * prefer the one nearest the controller rather than whatever [Bounds.positions] happens to visit
	 * first. [farRackPos] sits at `z = 0` (visited early - `z` is [net.minecraft.core.BlockPos.betweenClosed]'s
	 * slowest-varying, outermost axis) but is spatially far from [controllerPos]; [nearRackPos] sits
	 * right next to the controller but at `z = 4`, so it's visited late in raw scan order. A distance
	 * sort has to actually be happening for the near one to win.
	 */
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testPutAwayPrefersNearestEmptyRack() {
		val controllerPos = BlockPos(0, 2, 4)
		val cornerTwoPos = BlockPos(4, 3, 0)
		val nearRackPos = BlockPos(1, 2, 4)
		val farRackPos = BlockPos(4, 2, 0)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(nearRackPos, Blocks.CHEST.defaultBlockState())
		setBlock(farRackPos, Blocks.CHEST.defaultBlockState())

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))
		controller.inboundBuffer.insert(ItemResource.of(ItemStack(Items.DIAMOND)), 5, false)

		succeedWhen {
			val nearRack = getBlockEntity(nearRackPos) as ChestBlockEntity
			val farRack = getBlockEntity(farRackPos) as ChestBlockEntity
			assertTrue(nearRack.getItem(0).`is`(Items.DIAMOND) && nearRack.getItem(0).count == 5) {
				"Expected the 5 diamonds to have been stowed into the nearer rack, got ${nearRack.getItem(0)}"
			}
			assertTrue(farRack.getItem(0).isEmpty) {
				"Expected the farther rack to have been left untouched, got ${farRack.getItem(0)}"
			}
		}
	}

	/**
	 * [WarehouseScale.fromBounds] picks a tier purely from [Bounds.railStructure]'s own block
	 * count, not the volume - a pure function of the corners, so this asserts directly against
	 * constructed [Bounds] rather than anything that needs the world to tick. Each footprint here
	 * is a single-Y-layer square (`min.y == max.y`, so [Bounds.railSupports] contributes nothing),
	 * sized so its border alone (`4 * side`) lands exactly on, or just under, [WarehouseScale.COMPACT]/
	 * [WarehouseScale.REGIONAL]'s own `maxBlockCount` ceiling - pinning that `fromBounds` treats a
	 * ceiling count as already having crossed into the next tier (`count < maxBlockCount`, not `<=`).
	 */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testWarehouseScaleThresholds() {
		fun squareBounds(side: Int) = Bounds.of(BlockPos(0, 0, 0), BlockPos(side, 0, side))

		val comfortablyCompact = squareBounds(100) // border of 400, well under COMPACT's 500 ceiling
		assertTrue(WarehouseScale.fromBounds(comfortablyCompact) == WarehouseScale.COMPACT) {
			"Expected a border of 400 blocks to stay COMPACT, got ${WarehouseScale.fromBounds(comfortablyCompact)}"
		}

		val atCompactCeiling = squareBounds(125) // border of exactly 500
		assertTrue(WarehouseScale.fromBounds(atCompactCeiling) == WarehouseScale.COMPACT) {
			"Expected a border of exactly 500 blocks (COMPACT's own ceiling) to still be COMPACT, got ${WarehouseScale.fromBounds(atCompactCeiling)}"
		}

		val justOverCompactCeiling = squareBounds(126) // border of 504, just past COMPACT's 500 ceiling
		assertTrue(WarehouseScale.fromBounds(justOverCompactCeiling) == WarehouseScale.REGIONAL) {
			"Expected a border of 504 blocks to already be REGIONAL, got ${WarehouseScale.fromBounds(justOverCompactCeiling)}"
		}

		val atMegaCeiling = squareBounds(625) // border of exactly 2500
		assertTrue(WarehouseScale.fromBounds(atMegaCeiling) == WarehouseScale.REGIONAL) {
			"Expected a border of exactly 2500 blocks (REGIONAL's own ceiling) to still be REGIONAL, got ${WarehouseScale.fromBounds(atMegaCeiling)}"
		}

		val justOverMegaCeiling = squareBounds(626) // border of 2504, just past REGIONAL's 2500 ceiling
		assertTrue(WarehouseScale.fromBounds(justOverMegaCeiling) == WarehouseScale.MEGA) {
			"Expected a border of 2504 blocks to already be MEGA, got ${WarehouseScale.fromBounds(justOverMegaCeiling)}"
		}

		succeed()
	}

	/**
	 * [WarehouseScale.REGIONAL] drives indexing through [ChunkParallelScanTask][net.kernelpanicsoft.boilerplate.warehouse.ChunkParallelScanTask]
	 * and frame placement through [WarehouseControllerBlockEntity.placeFrameAsync] instead of
	 * [WarehouseScale.COMPACT]'s single-tick versions - crossed here by height, not footprint: every
	 * concurrently-running gametest in the batch gets its own offset in the SAME shared world, so a
	 * wide bound footprint risks actually reaching a *different* test's structure and indexing
	 * *its* chests (confirmed the hard way - an earlier version of this test using a 150-block-wide
	 * footprint picked up rack positions from unrelated tests dozens of blocks away and silently
	 * stowed into one of them instead). [Bounds.railSupports]' four corner columns scale with
	 * `max.y - min.y` alone, so stacking the bound volume 200 blocks tall on the same tiny footprint
	 * [rackPos] already sits on crosses REGIONAL's 500-block ceiling (16 perimeter + 4*200 = 816)
	 * without the footprint ever leaving this test's own loaded chunk.
	 *
	 * Also proves put-away actually completes despite the 200-block-tall rail:
	 * [WarehouseControllerBlockEntity.clearanceYFor] only ascends as high as the tallest rack
	 * [WarehouseIndex] actually knows about (here, [rackPos]'s own low height), not always this
	 * bound's own top - without that, a genuinely 200-block round trip at [WarehouseScale.baseSpeedPerTick]
	 * would take far longer than any gametest should wait on, orthogonal to what actually differs per
	 * [WarehouseScale] tier anyway (put-away's gantry-travel half is identical code regardless of
	 * scan strategy).
	 */
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testRegionalScaleIndexesRackAcrossTallBounds() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 202, 4)
		val rackPos = BlockPos(1, 2, 0)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(rackPos, Blocks.CHEST.defaultBlockState())

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		val bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))
		assertTrue(WarehouseScale.fromBounds(bounds) == WarehouseScale.REGIONAL) {
			"Expected this bound volume to land in the REGIONAL tier, got ${WarehouseScale.fromBounds(bounds)}"
		}
		controller.bounds = bounds
		controller.inboundBuffer.insert(ItemResource.of(ItemStack(Items.DIAMOND)), 5, false)

		succeedWhen {
			assertTrue(!controller.index.isRescanning) { "Expected the REGIONAL-scale scan to have finished by now" }
			assertTrue(controller.index.availableSlots.any { it.first == absolutePos(rackPos) }) {
				"Expected the REGIONAL-scale scan to have indexed the rack at $rackPos, got ${controller.index.availableSlots}"
			}
			val rack = getBlockEntity(rackPos) as ChestBlockEntity
			assertTrue(rack.getItem(0).`is`(Items.DIAMOND) && rack.getItem(0).count == 5) {
				"Expected the 5 diamonds to have been stowed into the rack despite the tall rail, got ${rack.getItem(0)}"
			}
		}
	}

	/**
	 * As [testRegionalScaleIndexesRackAcrossTallBounds], but tall enough to cross REGIONAL's own
	 * 2500-block ceiling (16 perimeter + 4*700 = 2816) instead, so binding drives indexing through
	 * [IncrementalTickScanTask][net.kernelpanicsoft.boilerplate.warehouse.IncrementalTickScanTask]
	 * and frame placement through [WarehouseControllerBlockEntity.queueFrameOperation]/[WarehouseControllerBlockEntity.tickFrameQueue]
	 * instead - the time-budgeted, tick-spread versions a real million-block warehouse needs.
	 * [edgeFramePos] sits on one of [Bounds.railSupports]' corner columns, low enough to fall inside
	 * this test's own loaded chunk, so it doubles as proof the ticked queue actually placed a real
	 * frame block rather than just leaving [WarehouseControllerBlockEntity]'s pending queue full.
	 */
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testMegaScaleIndexesRackAcrossTallBounds() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 702, 4)
		val rackPos = BlockPos(1, 2, 0)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(rackPos, Blocks.CHEST.defaultBlockState())

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		val bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))
		assertTrue(WarehouseScale.fromBounds(bounds) == WarehouseScale.MEGA) {
			"Expected this bound volume to land in the MEGA tier, got ${WarehouseScale.fromBounds(bounds)}"
		}
		controller.bounds = bounds
		controller.inboundBuffer.insert(ItemResource.of(ItemStack(Items.DIAMOND)), 5, false)

		succeedWhen {
			assertTrue(!controller.index.isRescanning) { "Expected the MEGA-scale scan to have finished by now" }
			assertTrue(controller.index.availableSlots.any { it.first == absolutePos(rackPos) }) {
				"Expected the MEGA-scale scan to have indexed the rack at $rackPos, got ${controller.index.availableSlots}"
			}
			val edgeFramePos = BlockPos(4, 2, 0)
			assertTrue(getBlockState(edgeFramePos).block == BlockRegistry.GantryRail) {
				"Expected the loaded portion of the border near the controller to have been framed via the ticked queue, got ${getBlockState(edgeFramePos)}"
			}
			val rack = getBlockEntity(rackPos) as ChestBlockEntity
			assertTrue(rack.getItem(0).`is`(Items.DIAMOND) && rack.getItem(0).count == 5) {
				"Expected the 5 diamonds to have been stowed into the rack despite the tall rail, got ${rack.getItem(0)}"
			}
		}
	}

	/**
	 * [WarehouseControllerBlockEntity.tickIndex]'s own `index.isRescanning` branch only ever skips
	 * that tick's index-scan-progression logic, never `tickJobs`/`gantry.tick` - forcing a fresh
	 * rescan mid-delivery (standing in for what the periodic background audit does on its own
	 * schedule, without this test having to wait out the real interval) shouldn't reset, pause, or
	 * otherwise disrupt an already-in-flight job; the index accounting the rescan is doing runs
	 * entirely independently of it.
	 */
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testPutAwayContinuesDuringBackgroundRescan() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val rackPos = BlockPos(3, 2, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(rackPos, Blocks.CHEST.defaultBlockState())

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		val bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))
		controller.bounds = bounds
		controller.inboundBuffer.insert(ItemResource.of(ItemStack(Items.DIAMOND)), 5, false)

		var forcedRescanMidFlight = false

		succeedWhen {
			if (!forcedRescanMidFlight && controller.gantry.isMoving) {
				controller.index.scheduleRescan(level, bounds, WarehouseScale.fromBounds(bounds), absolutePos(controllerPos))
				forcedRescanMidFlight = true
			}

			assertTrue(forcedRescanMidFlight) { "Expected the gantry to have started moving toward the rack by now" }

			val rack = getBlockEntity(rackPos) as ChestBlockEntity
			assertTrue(rack.getItem(0).`is`(Items.DIAMOND) && rack.getItem(0).count == 5) {
				"Expected the 5 diamonds to have been stowed despite a rescan being forced mid-delivery, got ${rack.getItem(0)}"
			}
			assertTrue(controller.inboundBuffer.getAmount(0) == 0L) {
				"Expected the inbound buffer to be empty after stowing, got amount ${controller.inboundBuffer.getAmount(0)}"
			}
		}
	}

	/**
	 * Regression test: [WarehouseIndex.updateSinglePosition] (driven by
	 * [WarehouseBlockEventListener]'s place/break hooks) updated [WarehouseIndex.locations]/
	 * [WarehouseIndex.knownContainers] for a rack placed after the initial scan, but never refreshed
	 * [WarehouseIndex.availableSlots] too - so a freshly-placed *empty* rack (nothing for
	 * [WarehouseControllerBlockEntity.bestRackFor]'s first, already-stocked-locations search to
	 * match) sat in [WarehouseIndex.knownContainers] correctly but was invisible to put-away's own
	 * empty-rack fallback until the next full audit, indistinguishable from the warehouse just not
	 * noticing the new rack exists at all.
	 */
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testPutAwayPicksUpRackPlacedAfterBinding() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val rackPos = BlockPos(3, 2, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))

		var rackPlaced = false

		succeedWhen {
			if (!rackPlaced && !controller.index.isRescanning) {
				// Wait for the initial (empty-warehouse) scan to actually finish before placing the
				// rack, so this exercises the incremental place-hook path, not the initial full scan.
				setBlock(rackPos, Blocks.CHEST.defaultBlockState())
				controller.inboundBuffer.insert(ItemResource.of(ItemStack(Items.DIAMOND)), 5, false)
				rackPlaced = true
			}
			assertTrue(rackPlaced) { "Expected the initial scan to have finished by now" }

			val rack = getBlockEntity(rackPos) as ChestBlockEntity
			assertTrue(rack.getItem(0).`is`(Items.DIAMOND) && rack.getItem(0).count == 5) {
				"Expected the 5 diamonds to have been stowed into the rack placed after binding, got ${rack.getItem(0)}"
			}
		}
	}

	/**
	 * [net.kernelpanicsoft.boilerplate.warehouse.WarehouseDefragPlanner] should consolidate a
	 * resource scattered across two racks into whichever already holds more of it, via a real
	 * [net.kernelpanicsoft.boilerplate.warehouse.GantryJob.Move] round trip - not through either
	 * staging buffer.
	 */
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testDefragConsolidatesScatteredStackIntoOneRack() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val smallerRackPos = BlockPos(1, 2, 0)
		val biggerRackPos = BlockPos(4, 2, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(smallerRackPos, Blocks.CHEST.defaultBlockState())
		setBlock(biggerRackPos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(smallerRackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 3))
		(getBlockEntity(biggerRackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 5))

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))
		val resource = ItemResource.of(ItemStack(Items.DIAMOND))

		var defragQueued = false

		succeedWhen {
			if (!defragQueued) {
				val entries = controller.index.slotsFor(resource)
				if (entries.size == 2) {
					controller.enqueueDefrag(level)
					defragQueued = true
				}
			}
			assertTrue(defragQueued) { "Expected the initial scan to have indexed both scattered chests by now" }

			val smallerRack = getBlockEntity(smallerRackPos) as ChestBlockEntity
			val biggerRack = getBlockEntity(biggerRackPos) as ChestBlockEntity
			assertTrue(smallerRack.getItem(0).isEmpty) {
				"Expected defrag to have drained the smaller rack into the bigger one, got ${smallerRack.getItem(0)}"
			}
			assertTrue(biggerRack.getItem(0).`is`(Items.DIAMOND) && biggerRack.getItem(0).count == 8) {
				"Expected the bigger rack to hold all 8 diamonds after defrag, got ${biggerRack.getItem(0)}"
			}
			val entries = controller.index.slotsFor(resource)
			assertTrue(entries.size == 1 && entries[0].amount == 8L) {
				"Expected the index to reflect one consolidated entry of 8 after defrag, got $entries"
			}
			assertTrue(!controller.gantry.isMoving) { "Expected the gantry to have finished and returned home by now" }
		}
	}
}
