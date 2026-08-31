package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.boilerplate.network.UpdateSortingRoutingPacket
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.ProviderHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.ProviderHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.FilterHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.SyncHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.BooleanOperator
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.CombinedConditionState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.CombinedConditionType
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.ItemConditionState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.ModConditionState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.ModConditionType
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.RegexConditionState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.RegexConditionType
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.RegistryAccess
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.phys.shapes.CollisionContext

/**
 * GameTest coverage for the real extraction/travel/insertion pipeline
 * ([net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionHookType.tick] pulling into a
 * [PipeBlockEntity]'s [net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem] queue, then
 * [PipeBlockEntity.tick] advancing and finally inserting it), M2's sorting-hook filtering, and M3's
 * request-based routing ([net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookType] pulling
 * from a [net.kernelpanicsoft.boilerplate.pipe.hook.ProviderHookType] via
 * [net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment]), and M3's default route
 * ([net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule.DEFAULT_ROUTE_PRIORITY]). Unlike
 * [PipeNetworkGameTest], these place real blocks and let the world tick, rather than driving
 * [net.kernelpanicsoft.boilerplate.pipe.network.PipeNetworkManager] directly.
 */
@Suppress("unused")
class PipeExtractionGameTest {
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testExtractorDeliversItemToAdjacentChest() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val destPos = BlockPos(0, 2, 2)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.DIAMOND) && dest.getItem(0).count == 8) {
				"Expected 8 diamonds to have arrived in the destination chest, got ${dest.getItem(0)}"
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testSortingPipeDeliversMatchingFilteredItem() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val sortPipePos = BlockPos(0, 2, 2)
		val destPos = BlockPos(0, 2, 3)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(sortPipePos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 4))
		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		val sortPipe = getBlockEntity(sortPipePos) as MultipartBlockEntity
		sortPipe.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val sortState = sortPipe.hooks.getOrPut(Direction.SOUTH.name) { FilterHookType.createState() } as SortingHookState
		sortState.routing = RoutingModule(mode = FilterMode.WHITELIST)
		sortPipe.filterFor(Direction.SOUTH).insert(ItemResource.of(buildItemCard(ItemStack(Items.DIAMOND))), 1, false)

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.DIAMOND) && dest.getItem(0).count == 4) {
				"Expected 4 diamonds to have arrived past the whitelist filter, got ${dest.getItem(0)}"
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testSortingPipeRejectsNonMatchingFilteredItem() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val sortPipePos = BlockPos(0, 2, 2)
		val destPos = BlockPos(0, 2, 3)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(sortPipePos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.REDSTONE, 4))
		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		val sortPipe = getBlockEntity(sortPipePos) as MultipartBlockEntity
		sortPipe.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val sortState = sortPipe.hooks.getOrPut(Direction.SOUTH.name) { FilterHookType.createState() } as SortingHookState
		sortState.routing = RoutingModule(mode = FilterMode.WHITELIST)
		sortPipe.filterFor(Direction.SOUTH).insert(ItemResource.of(buildItemCard(ItemStack(Items.DIAMOND))), 1, false)

		// No route ever exists for the redstone (the only sorting-tagged path rejects it, and the
		// source chest is excluded from being its own destination), so - unlike the "eventually
		// happens" assertions above - this needs to observe an absence holding steady, not wait for
		// a condition to become true. runAfterDelay checks once, well past two extraction intervals.
		runAfterDelay(100) {
			val source = getBlockEntity(sourcePos) as ChestBlockEntity
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(source.getItem(0).`is`(Items.REDSTONE) && source.getItem(0).count == 4) {
				"Expected the filtered-out redstone to remain unextracted in the source chest, got ${source.getItem(0)}"
			}
			assertTrue(dest.getItem(0).isEmpty) { "Expected the destination chest to stay empty, got ${dest.getItem(0)}" }
			succeed()
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testRequesterHookPullsFromProviderHook() {
		val sourcePos = BlockPos(0, 2, 0)
		val providerHookPos = BlockPos(0, 2, 1)
		val requesterHookPos = BlockPos(0, 2, 2)
		val destPos = BlockPos(0, 2, 3)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(providerHookPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(requesterHookPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val provider = getBlockEntity(providerHookPos) as MultipartBlockEntity
		provider.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val providerState = provider.hooks.getOrPut(Direction.NORTH.name) { ProviderHookType.createState() } as ProviderHookState
		providerState.routing = RoutingModule(mode = FilterMode.BLACKLIST)
		placeCreativePressureSource(providerHookPos.above())

		val requester = getBlockEntity(requesterHookPos) as MultipartBlockEntity
		requester.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val requesterState = requester.hooks.getOrPut(Direction.SOUTH.name) { RequesterHookType.createState() } as RequesterHookState
		requesterState.request.insert(ItemResource.of(ItemStack(Items.DIAMOND)), 4, false)

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.DIAMOND) && dest.getItem(0).count == 4) {
				"Expected 4 diamonds (the requester's standing order) to have arrived, got ${dest.getItem(0)}"
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testDefaultRouteWinsWhenNothingElseAccepts() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val defaultHookPos = BlockPos(0, 2, 2)
		val defaultDestPos = BlockPos(0, 2, 3)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(defaultHookPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(defaultDestPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		val defaultHook = getBlockEntity(defaultHookPos) as MultipartBlockEntity
		defaultHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val defaultState = defaultHook.hooks.getOrPut(Direction.SOUTH.name) { FilterHookType.createState() } as SortingHookState
		defaultState.routing = RoutingModule(mode = FilterMode.BLACKLIST, priority = RoutingModule.DEFAULT_ROUTE_PRIORITY)

		succeedWhen {
			val dest = getBlockEntity(defaultDestPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.DIAMOND) && dest.getItem(0).count == 8) {
				"Expected 8 diamonds with nowhere else to go to have gone to the default route, got ${dest.getItem(0)}"
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testDefaultRouteLosesToOrdinaryDestination() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val midPos = BlockPos(0, 2, 2)
		val ordinaryDestPos = BlockPos(0, 2, 3)
		val defaultHookPos = BlockPos(1, 2, 2)
		val defaultDestPos = BlockPos(2, 2, 2)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(midPos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(ordinaryDestPos, Blocks.CHEST.defaultBlockState())
		setBlock(defaultHookPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(defaultDestPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		val defaultHook = getBlockEntity(defaultHookPos) as MultipartBlockEntity
		defaultHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val defaultState = defaultHook.hooks.getOrPut(Direction.EAST.name) { FilterHookType.createState() } as SortingHookState
		defaultState.routing = RoutingModule(mode = FilterMode.BLACKLIST, priority = RoutingModule.DEFAULT_ROUTE_PRIORITY)

		succeedWhen {
			val ordinaryDest = getBlockEntity(ordinaryDestPos) as ChestBlockEntity
			assertTrue(ordinaryDest.getItem(0).`is`(Items.DIAMOND) && ordinaryDest.getItem(0).count == 8) {
				"Expected 8 diamonds to have gone to the ordinary destination rather than the default route, got ${ordinaryDest.getItem(0)}"
			}
			val defaultDest = getBlockEntity(defaultDestPos) as ChestBlockEntity
			assertTrue(defaultDest.getItem(0).isEmpty) { "Expected nothing to have gone to the default route while an ordinary destination existed, got ${defaultDest.getItem(0)}" }
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testOnlyOneDefaultRoutePerNetwork() {
		val hookAPos = BlockPos(0, 2, 0)
		val hookBPos = BlockPos(0, 2, 1)
		setBlock(hookAPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(hookBPos, BlockRegistry.Multipart.defaultBlockState())

		val hookA = getBlockEntity(hookAPos) as MultipartBlockEntity
		hookA.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val stateA = hookA.hooks.getOrPut(Direction.NORTH.name) { FilterHookType.createState() } as SortingHookState
		stateA.routing = RoutingModule(mode = FilterMode.BLACKLIST, priority = RoutingModule.DEFAULT_ROUTE_PRIORITY)

		val hookB = getBlockEntity(hookBPos) as MultipartBlockEntity
		hookB.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		hookB.hooks.getOrPut(Direction.SOUTH.name) { FilterHookType.createState() }

		// PipeNetworkManager only registers a pipe into its network from PipeBlockEntity.tick(), so
		// the world needs a few ticks to run first - otherwise networkIdAt(pos) is still null and
		// clearOtherDefaultRoutes has nothing to walk.
		runAfterDelay(5) {
			val fakeContext = object : IPacketContext {
				override val player: Player = makeMockPlayer(GameType.CREATIVE)
				override val registryAccess: RegistryAccess = level.registryAccess()
			}
			val newDefaultRoute = RoutingModule(mode = FilterMode.BLACKLIST, priority = RoutingModule.DEFAULT_ROUTE_PRIORITY)
			UpdateSortingRoutingPacket(absolutePos(hookBPos), Direction.SOUTH, newDefaultRoute).handleOnServer(fakeContext)

			assertTrue(stateA.routing.priority == 0) {
				"Expected hook A's default-route claim to have been cleared once hook B claimed it, got priority ${stateA.routing.priority}"
			}
			val stateB = hookB.hooks[Direction.SOUTH.name] as SortingHookState
			assertTrue(stateB.routing.priority == RoutingModule.DEFAULT_ROUTE_PRIORITY) {
				"Expected hook B to now hold the default route, got priority ${stateB.routing.priority}"
			}
			succeed()
		}
	}

	/**
	 * A hookless face on a [MultipartBlockEntity] that also carries a [TerminalHookType] hook
	 * (on any other face) is the terminal's own well-defined withdrawal destination -
	 * `WarehouseTerminalMenu.adjacentInventory` - and must never be a [PipeRouter][net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter]
	 * push candidate, or a default route/extractor push could dump straight into it instead of the
	 * player choosing to withdraw. An ordinary hookless chest elsewhere on the network is unaffected.
	 */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testTerminalConnectionExcludedFromPushRouting() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val midPos = BlockPos(0, 2, 2)
		val ordinaryDestPos = BlockPos(0, 2, 3)
		val terminalHookPos = BlockPos(1, 2, 2)
		val terminalChestPos = BlockPos(2, 2, 2)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(midPos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(ordinaryDestPos, Blocks.CHEST.defaultBlockState())
		setBlock(terminalHookPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(terminalChestPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		val terminalHook = getBlockEntity(terminalHookPos) as MultipartBlockEntity
		terminalHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		terminalHook.hooks.getOrPut(Direction.UP.name) { TerminalHookType.createState() }

		succeedWhen {
			val ordinaryDest = getBlockEntity(ordinaryDestPos) as ChestBlockEntity
			assertTrue(ordinaryDest.getItem(0).`is`(Items.DIAMOND) && ordinaryDest.getItem(0).count == 8) {
				"Expected 8 diamonds to have gone to the ordinary destination rather than the terminal's own chest, got ${ordinaryDest.getItem(0)}"
			}
			val terminalChest = getBlockEntity(terminalChestPos) as ChestBlockEntity
			assertTrue(terminalChest.getItem(0).isEmpty) {
				"Expected nothing to have been pushed into the terminal's own bare inventory connection, got ${terminalChest.getItem(0)}"
			}
		}
	}

	/**
	 * A Crafting CPU's own storage is a specific job's reserved working space, fed only by that job's
	 * own deliberate claim/feed logic (both
	 * [PipeRouter][net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter.findRouteTo]-targeted) -
	 * never a [PipeRouter.findRoute][net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter.findRoute]
	 * push candidate, or an unrelated extractor's push (or another cluster's own drain leg) could
	 * dump straight into it. Being an encased pipe segment, it's ordinary *transit* to that BFS
	 * rather than a candidate destination - this covers that it stays that way. The CPU is
	 * deliberately the *only* other block reachable from the extractor here, so nothing else can
	 * coincidentally win the route and mask a regression.
	 */
	@GameTest(template = SMALL, timeoutTicks = 100)
	fun GameTestHelper.testCraftingCpuExcludedFromPushRouting() {
		val sourcePos = BlockPos(4, 2, 3)
		val extractorPos = BlockPos(4, 2, 4)
		// Kept well inside the template envelope - gametest structures are packed wall-to-wall, and
		// a buffer touching a boundary clusters with whatever the neighboring test places across it.
		val cpuPos = BlockPos(4, 2, 5)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		val cpuTile = placeCraftingBuffer(cpuPos)

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }

		runAfterDelay(80) {
			val source = getBlockEntity(sourcePos) as ChestBlockEntity
			assertTrue(source.getItem(0).`is`(Items.DIAMOND) && source.getItem(0).count == 8) {
				"Expected the diamonds to have stayed put - the Crafting CPU is the only other reachable block, and must never be a valid push destination - got ${source.getItem(0)}"
			}
			val cpu = cpuTile.craftingBuffer
			assertTrue(cpu.combinedStorage(cpuTile).let { storage -> (0 until storage.size()).none { !storage.get(it).resource.isBlank } }) {
				"Expected nothing to have been pushed into the Crafting CPU's own storage"
			}
			succeed()
		}
	}

	/**
	 * Regression test for a real crash: [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock.getShape]
	 * used to cast a `NestedNBTHolderMap` iteration entry *itself* to
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.HookHolderState] instead of its own `.value` -
	 * `Map.Entry` isn't a `HookHolderState`, so any raycast against a hooked block (a player simply
	 * looking at one) threw a `ClassCastException` straight out of `BlockBehaviour.getShape`. Covers
	 * both [ExtractionHookType] (the plain default shape every hook but one shares) and
	 * [TerminalHookType] (its own wider override), since the bug was in the shared per-hook lookup,
	 * not either shape definition itself.
	 */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testHookedBlockShapeDoesNotCrash() {
		val extractorPos = BlockPos(0, 2, 0)
		val terminalPos = BlockPos(0, 2, 2)
		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(terminalPos, BlockRegistry.Multipart.defaultBlockState())

		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }

		val terminal = getBlockEntity(terminalPos) as MultipartBlockEntity
		terminal.hooks.getOrPut(Direction.UP.name) { TerminalHookType.createState() }

		val absoluteExtractorPos = absolutePos(extractorPos)
		val extractorShape = level.getBlockState(absoluteExtractorPos).getShape(level, absoluteExtractorPos, CollisionContext.empty())
		assertTrue(!extractorShape.isEmpty) {
			"Expected the extraction hook's own shape to be non-empty, got $extractorShape"
		}

		val absoluteTerminalPos = absolutePos(terminalPos)
		val terminalShape = level.getBlockState(absoluteTerminalPos).getShape(level, absoluteTerminalPos, CollisionContext.empty())
		assertTrue(!terminalShape.isEmpty) {
			"Expected the terminal hook's own shape to be non-empty, got $terminalShape"
		}

		succeed()
	}

	/**
	 * [SyncHookType] layers [ProviderHookType]'s request-fulfillment sourcing onto [FilterHookType]'s
	 * own filter grid ([SortingHookState.accepts]) - a requester can pull through a sync hook whose
	 * whitelist actually matches the requested resource.
	 */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testRequesterHookPullsFromSyncHookMatchingFilter() {
		val sourcePos = BlockPos(0, 2, 0)
		val syncHookPos = BlockPos(0, 2, 1)
		val requesterHookPos = BlockPos(0, 2, 2)
		val destPos = BlockPos(0, 2, 3)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(syncHookPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(requesterHookPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val sync = getBlockEntity(syncHookPos) as MultipartBlockEntity
		sync.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val syncState = sync.hooks.getOrPut(Direction.NORTH.name) { SyncHookType.createState() } as SortingHookState
		syncState.routing = RoutingModule(mode = FilterMode.WHITELIST)
		sync.filterFor(Direction.NORTH).insert(ItemResource.of(buildItemCard(ItemStack(Items.DIAMOND))), 1, false)
		placeCreativePressureSource(syncHookPos.above())

		val requester = getBlockEntity(requesterHookPos) as MultipartBlockEntity
		requester.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val requesterState = requester.hooks.getOrPut(Direction.SOUTH.name) { RequesterHookType.createState() } as RequesterHookState
		requesterState.request.insert(ItemResource.of(ItemStack(Items.DIAMOND)), 4, false)

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.DIAMOND) && dest.getItem(0).count == 4) {
				"Expected 4 diamonds to have arrived past the sync hook's matching filter, got ${dest.getItem(0)}"
			}
		}
	}

	/**
	 * Regression coverage for the gap [SortingHookState.accepts] closed: a [SyncHookType] hook's own
	 * filter used to be purely decorative for request fulfillment - [RequestFulfillment] treated it
	 * exactly like a filter-less [ProviderHookType], pulling anything the adjacent inventory had
	 * regardless of what the sync hook's whitelist/blacklist actually said.
	 */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testRequesterHookSkipsSyncHookWithNonMatchingFilter() {
		val sourcePos = BlockPos(0, 2, 0)
		val syncHookPos = BlockPos(0, 2, 1)
		val requesterHookPos = BlockPos(0, 2, 2)
		val destPos = BlockPos(0, 2, 3)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(syncHookPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(requesterHookPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val sync = getBlockEntity(syncHookPos) as MultipartBlockEntity
		sync.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val syncState = sync.hooks.getOrPut(Direction.NORTH.name) { SyncHookType.createState() } as SortingHookState
		syncState.routing = RoutingModule(mode = FilterMode.WHITELIST)
		sync.filterFor(Direction.NORTH).insert(ItemResource.of(buildItemCard(ItemStack(Items.REDSTONE))), 1, false)
		placeCreativePressureSource(syncHookPos.above())

		val requester = getBlockEntity(requesterHookPos) as MultipartBlockEntity
		requester.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val requesterState = requester.hooks.getOrPut(Direction.SOUTH.name) { RequesterHookType.createState() } as RequesterHookState
		requesterState.request.insert(ItemResource.of(ItemStack(Items.DIAMOND)), 4, false)

		runAfterDelay(100) {
			val source = getBlockEntity(sourcePos) as ChestBlockEntity
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(source.getItem(0).`is`(Items.DIAMOND) && source.getItem(0).count == 8) {
				"Expected the diamonds to remain unpulled through the sync hook's non-matching filter, got ${source.getItem(0)}"
			}
			assertTrue(dest.getItem(0).isEmpty) { "Expected the destination chest to stay empty, got ${dest.getItem(0)}" }
			succeed()
		}
	}

	/** A [ModConditionType] filter card, dropped into a hook's ghost filter grid, routes by mod namespace rather than exact item identity. */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testFilterCardModConditionRoutesByNamespace() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val sortPipePos = BlockPos(0, 2, 2)
		val destPos = BlockPos(0, 2, 3)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(sortPipePos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 4))
		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		val sortPipe = getBlockEntity(sortPipePos) as MultipartBlockEntity
		sortPipe.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val sortState = sortPipe.hooks.getOrPut(Direction.SOUTH.name) { FilterHookType.createState() } as SortingHookState
		sortState.routing = RoutingModule(mode = FilterMode.WHITELIST)

		val modCard = ItemStack(ItemRegistry.ModFilterCard)
		val modCardState = FilterCardState(modCard)
		(modCardState.currentState() as ModConditionState).modId = "minecraft"
		modCardState.touchCurrentState()
		sortPipe.filterFor(Direction.SOUTH).insert(ItemResource.of(modCard), 1, false)

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.DIAMOND) && dest.getItem(0).count == 4) {
				"Expected 4 diamonds to have arrived past the mod filter card's minecraft-namespace match, got ${dest.getItem(0)}"
			}
		}
	}

	/**
	 * Builds a [CombinedConditionType] card set to [BooleanOperator.AND] over a mod card
	 * (`"minecraft"`) and a regex card (`"diamond"`) - both children must have
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState.touchCurrentState]d
	 * before their [ItemResource] snapshot is taken, or the ghost slot captures each card's
	 * *default-valued* state instead of what was actually configured (see
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState.touchCurrentState]'s own
	 * KDoc) - which [testFilterCardCombinedAndRejectsPartialMatch] alone can't tell apart from
	 * correct AND-rejection, since a permanently-blank child also never matches anything.
	 * [testFilterCardCombinedAndRoutesFullMatch] is what actually catches that regression.
	 */
	private fun buildMinecraftAndDiamondCombinedCard(): ItemStack {
		val modCard = ItemStack(ItemRegistry.ModFilterCard)
		FilterCardState(modCard).apply {
			(currentState() as ModConditionState).modId = "minecraft"
			touchCurrentState()
		}

		val regexCard = ItemStack(ItemRegistry.RegexFilterCard)
		FilterCardState(regexCard).apply {
			(currentState() as RegexConditionState).regex = "diamond"
			touchCurrentState()
		}

		val combinedCard = ItemStack(ItemRegistry.CombinedFilterCard)
		FilterCardState(combinedCard).apply {
			(currentState() as CombinedConditionState).apply {
				operator = BooleanOperator.AND
				children[0] = ItemResource.of(modCard)
				children[1] = ItemResource.of(regexCard)
			}
			touchCurrentState()
		}
		return combinedCard
	}

	/** A [BooleanOperator.AND] combined card only routes an item matching *both* children - a mod-only match (redstone: minecraft namespace, but fails the "diamond" regex) must not be enough on its own. */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testFilterCardCombinedAndRejectsPartialMatch() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val sortPipePos = BlockPos(0, 2, 2)
		val destPos = BlockPos(0, 2, 3)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(sortPipePos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.REDSTONE, 4))
		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		val sortPipe = getBlockEntity(sortPipePos) as MultipartBlockEntity
		sortPipe.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val sortState = sortPipe.hooks.getOrPut(Direction.SOUTH.name) { FilterHookType.createState() } as SortingHookState
		sortState.routing = RoutingModule(mode = FilterMode.WHITELIST)
		sortPipe.filterFor(Direction.SOUTH).insert(ItemResource.of(buildMinecraftAndDiamondCombinedCard()), 1, false)

		// No route ever exists for the redstone, so this needs to observe an absence holding
		// steady rather than wait for a condition to become true, same as the plain sorting-pipe
		// rejection test above.
		runAfterDelay(100) {
			val source = getBlockEntity(sourcePos) as ChestBlockEntity
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(source.getItem(0).`is`(Items.REDSTONE) && source.getItem(0).count == 4) {
				"Expected the redstone (mod matches, regex doesn't) to remain unextracted under AND, got ${source.getItem(0)}"
			}
			assertTrue(dest.getItem(0).isEmpty) { "Expected the destination chest to stay empty, got ${dest.getItem(0)}" }
			succeed()
		}
	}

	/** The same [BooleanOperator.AND] combined card as [testFilterCardCombinedAndRejectsPartialMatch] actually routes an item matching *both* children - diamond is `minecraft:diamond`, satisfying the mod card and the regex card alike. */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testFilterCardCombinedAndRoutesFullMatch() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val sortPipePos = BlockPos(0, 2, 2)
		val destPos = BlockPos(0, 2, 3)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(sortPipePos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 4))
		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		val sortPipe = getBlockEntity(sortPipePos) as MultipartBlockEntity
		sortPipe.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val sortState = sortPipe.hooks.getOrPut(Direction.SOUTH.name) { FilterHookType.createState() } as SortingHookState
		sortState.routing = RoutingModule(mode = FilterMode.WHITELIST)
		sortPipe.filterFor(Direction.SOUTH).insert(ItemResource.of(buildMinecraftAndDiamondCombinedCard()), 1, false)

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.DIAMOND) && dest.getItem(0).count == 4) {
				"Expected 4 diamonds (matching both the mod and regex children) to have arrived, got ${dest.getItem(0)}"
			}
		}
	}

	/** Builds an item filter card matching a custom-named diamond pickaxe, with [ItemConditionState.matchComponents] set as requested. */
	/** An [ItemRegistry.ItemFilterCard] matching [stack] by base item - a sorting hook's filter slot only accepts real filter cards now (see [net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState.filter]), so plain-item identity filtering goes through one of these rather than dropping the item itself into a ghost grid. */
	private fun buildItemCard(stack: ItemStack): ItemStack {
		val itemCard = ItemStack(ItemRegistry.ItemFilterCard)
		FilterCardState(itemCard).apply {
			(currentState() as ItemConditionState).itemMatches[0] = ItemResource.of(stack)
			touchCurrentState()
		}
		return itemCard
	}

	private fun buildNamedPickaxeItemCard(matchComponents: Boolean): ItemStack {
		val namedPickaxe = ItemStack(Items.DIAMOND_PICKAXE)
		namedPickaxe.set(DataComponents.CUSTOM_NAME, Component.literal("Special"))

		val itemCard = ItemStack(ItemRegistry.ItemFilterCard)
		FilterCardState(itemCard).apply {
			(currentState() as ItemConditionState).apply {
				itemMatches[0] = ItemResource.of(namedPickaxe)
				this.matchComponents = matchComponents
			}
			touchCurrentState()
		}
		return itemCard
	}

	/** With [ItemConditionState.matchComponents] on, a plain (unnamed) diamond pickaxe doesn't satisfy a card matching a custom-*named* one - same base item, different components. */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testFilterCardItemMatchComponentsRejectsDifferentComponents() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val sortPipePos = BlockPos(0, 2, 2)
		val destPos = BlockPos(0, 2, 3)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(sortPipePos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND_PICKAXE))
		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		val sortPipe = getBlockEntity(sortPipePos) as MultipartBlockEntity
		sortPipe.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val sortState = sortPipe.hooks.getOrPut(Direction.SOUTH.name) { FilterHookType.createState() } as SortingHookState
		sortState.routing = RoutingModule(mode = FilterMode.WHITELIST)
		sortPipe.filterFor(Direction.SOUTH).insert(ItemResource.of(buildNamedPickaxeItemCard(matchComponents = true)), 1, false)

		runAfterDelay(100) {
			val source = getBlockEntity(sourcePos) as ChestBlockEntity
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(source.getItem(0).`is`(Items.DIAMOND_PICKAXE) && !source.getItem(0).has(DataComponents.CUSTOM_NAME)) {
				"Expected the plain pickaxe to remain unextracted under matchComponents, got ${source.getItem(0)}"
			}
			assertTrue(dest.getItem(0).isEmpty) { "Expected the destination chest to stay empty, got ${dest.getItem(0)}" }
			succeed()
		}
	}

	/** The same card as [testFilterCardItemMatchComponentsRejectsDifferentComponents] does route an item whose components match exactly, including the custom name. */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testFilterCardItemMatchComponentsRoutesExactComponents() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val sortPipePos = BlockPos(0, 2, 2)
		val destPos = BlockPos(0, 2, 3)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(sortPipePos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		val namedPickaxe = ItemStack(Items.DIAMOND_PICKAXE)
		namedPickaxe.set(DataComponents.CUSTOM_NAME, Component.literal("Special"))
		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, namedPickaxe)
		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		val sortPipe = getBlockEntity(sortPipePos) as MultipartBlockEntity
		sortPipe.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val sortState = sortPipe.hooks.getOrPut(Direction.SOUTH.name) { FilterHookType.createState() } as SortingHookState
		sortState.routing = RoutingModule(mode = FilterMode.WHITELIST)
		sortPipe.filterFor(Direction.SOUTH).insert(ItemResource.of(buildNamedPickaxeItemCard(matchComponents = true)), 1, false)

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.DIAMOND_PICKAXE) && dest.getItem(0).get(DataComponents.CUSTOM_NAME) == Component.literal("Special")) {
				"Expected the exactly-named pickaxe to have arrived, got ${dest.getItem(0)}"
			}
		}
	}

	/** A warehouse controller reached through a bare (hookless) pipe face now ranks by its own routing priority - so it beats a plain chest's baseline `0` without needing the sorting hook the pipe skeleton used to demand. */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testWarehousePriorityBeatsOrdinaryDestinationViaBareFace() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val midPos = BlockPos(0, 2, 2)
		val warehousePos = BlockPos(0, 2, 3)
		val ordinaryPos = BlockPos(1, 2, 2)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(midPos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(warehousePos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(ordinaryPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		val controller = getBlockEntity(warehousePos) as WarehouseControllerBlockEntity
		controller.routing = RoutingModule(mode = FilterMode.BLACKLIST, priority = 5)

		succeedWhen {
			assertTrue(controller.inboundBuffer.getAmount(0) == 8L) {
				"Expected 8 diamonds to have been routed to the priority-5 warehouse over the plain chest, got buffer ${controller.inboundBuffer.getAmount(0)}"
			}
			val ordinary = getBlockEntity(ordinaryPos) as ChestBlockEntity
			assertTrue(ordinary.getItem(0).isEmpty) { "Expected nothing to have gone to the ordinary chest, got ${ordinary.getItem(0)}" }
		}
	}

	/** The controller's default-route sentinel (`-1`) ranks below an ordinary chest exactly like a sorting hook's did - the bare face keeps every M2 default-route guarantee. */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testWarehouseDefaultPriorityLosesToOrdinaryDestinationViaBareFace() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val midPos = BlockPos(0, 2, 2)
		val warehousePos = BlockPos(0, 2, 3)
		val ordinaryPos = BlockPos(1, 2, 2)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(midPos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(warehousePos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(ordinaryPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		val controller = getBlockEntity(warehousePos) as WarehouseControllerBlockEntity
		controller.routing = RoutingModule(mode = FilterMode.BLACKLIST, priority = RoutingModule.DEFAULT_ROUTE_PRIORITY)

		succeedWhen {
			val ordinary = getBlockEntity(ordinaryPos) as ChestBlockEntity
			assertTrue(ordinary.getItem(0).`is`(Items.DIAMOND) && ordinary.getItem(0).count == 8) {
				"Expected 8 diamonds to have gone to the ordinary chest over the default-route warehouse, got ${ordinary.getItem(0)}"
			}
			assertTrue(controller.inboundBuffer.getAmount(0) == 0L) {
				"Expected nothing to have landed in the default-route warehouse while an ordinary destination existed, got ${controller.inboundBuffer.getAmount(0)}"
			}
		}
	}

	/** With no ordinary destination in reach, a default-priority warehouse is still the network's catch-all through a bare face. */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testWarehouseDefaultRouteClaimsCatchAllViaBareFace() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val midPos = BlockPos(0, 2, 2)
		val warehousePos = BlockPos(0, 2, 3)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(midPos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(warehousePos, BlockRegistry.WarehouseController.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		val controller = getBlockEntity(warehousePos) as WarehouseControllerBlockEntity
		controller.routing = RoutingModule(mode = FilterMode.BLACKLIST, priority = RoutingModule.DEFAULT_ROUTE_PRIORITY)

		succeedWhen {
			assertTrue(controller.inboundBuffer.getAmount(0) == 8L) {
				"Expected 8 diamonds with nowhere else to go to have landed in the default-route warehouse via a bare face, got buffer ${controller.inboundBuffer.getAmount(0)}"
			}
		}
	}

	/** The controller's own [WarehouseControllerBlockEntity.filter]/[WarehouseControllerBlockEntity.routing] gate what a bare-face warehouse accepts - a whitelist card lets matching items in and rejects the rest to an ordinary destination. */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testWarehouseFilterGatesBareFaceRouting() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val midPos = BlockPos(0, 2, 2)
		val warehousePos = BlockPos(0, 2, 3)
		val ordinaryPos = BlockPos(1, 2, 2)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(midPos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(warehousePos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(ordinaryPos, Blocks.CHEST.defaultBlockState())

		val source = getBlockEntity(sourcePos) as ChestBlockEntity
		source.setItem(0, ItemStack(Items.DIAMOND, 4))
		source.setItem(1, ItemStack(Items.REDSTONE, 4))
		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		val controller = getBlockEntity(warehousePos) as WarehouseControllerBlockEntity
		controller.routing = RoutingModule(mode = FilterMode.WHITELIST, priority = 1)
		controller.filter.insert(ItemResource.of(buildItemCard(ItemStack(Items.DIAMOND))), 1, false)

		succeedWhen {
			val ordinary = getBlockEntity(ordinaryPos) as ChestBlockEntity
			assertTrue(ordinary.getItem(0).`is`(Items.REDSTONE) && ordinary.getItem(0).count == 4) {
				"Expected 4 redstone to have been routed to the ordinary chest past the warehouse's whitelist, got ${ordinary.getItem(0)}"
			}
			assertTrue(controller.inboundBuffer.getResource(0) == ItemResource.of(ItemStack(Items.DIAMOND)) && controller.inboundBuffer.getAmount(0) == 4L) {
				"Expected 4 diamonds to have been routed to the whitelisted warehouse, got ${controller.inboundBuffer.getResource(0)} x${controller.inboundBuffer.getAmount(0)}"
			}
		}
	}
}
