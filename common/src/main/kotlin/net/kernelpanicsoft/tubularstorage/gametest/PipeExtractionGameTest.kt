package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.archie.networking.IPacketContext
import net.kernelpanicsoft.tubularstorage.network.UpdateSortingRoutingPacket
import net.kernelpanicsoft.tubularstorage.pipe.entity.FilterMode
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.RoutingModule
import net.kernelpanicsoft.tubularstorage.pipe.hook.ExtractionHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.ExtractionHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.ProviderHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.RequesterHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.RequesterHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookType
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity

/**
 * GameTest coverage for the real extraction/travel/insertion pipeline
 * ([net.kernelpanicsoft.tubularstorage.pipe.hook.ExtractionHookType.tick] pulling into a
 * [PipeBlockEntity]'s [net.kernelpanicsoft.tubularstorage.pipe.entity.TravelingItem] queue, then
 * [PipeBlockEntity.tick] advancing and finally inserting it), M2's sorting-hook filtering, and M3's
 * request-based routing ([net.kernelpanicsoft.tubularstorage.pipe.hook.RequesterHookType] pulling
 * from a [net.kernelpanicsoft.tubularstorage.pipe.hook.ProviderHookType] via
 * [net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment]), and M3's default route
 * ([net.kernelpanicsoft.tubularstorage.pipe.entity.RoutingModule.DEFAULT_ROUTE_PRIORITY]). Unlike
 * [PipeNetworkGameTest], these place real blocks and let the world tick, rather than driving
 * [net.kernelpanicsoft.tubularstorage.pipe.network.PipeNetworkManager] directly.
 */
@Suppress("unused")
class PipeExtractionGameTest {
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testExtractorDeliversItemToAdjacentChest() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val destPos = BlockPos(0, 2, 2)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Hook.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val extractor = getBlockEntity(extractorPos) as HookBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }

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
		setBlock(extractorPos, BlockRegistry.Hook.defaultBlockState())
		setBlock(sortPipePos, BlockRegistry.Hook.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 4))
		val extractor = getBlockEntity(extractorPos) as HookBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }

		val sortPipe = getBlockEntity(sortPipePos) as HookBlockEntity
		sortPipe.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val sortState = sortPipe.hooks.getOrPut(Direction.SOUTH.name) { SortingHookType.createState() } as SortingHookState
		sortState.routing = RoutingModule(mode = FilterMode.WHITELIST)
		sortPipe.filterFor(Direction.SOUTH).insert(ItemResource.of(ItemStack(Items.DIAMOND)), 1, false)

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
		setBlock(extractorPos, BlockRegistry.Hook.defaultBlockState())
		setBlock(sortPipePos, BlockRegistry.Hook.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.REDSTONE, 4))
		val extractor = getBlockEntity(extractorPos) as HookBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }

		val sortPipe = getBlockEntity(sortPipePos) as HookBlockEntity
		sortPipe.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val sortState = sortPipe.hooks.getOrPut(Direction.SOUTH.name) { SortingHookType.createState() } as SortingHookState
		sortState.routing = RoutingModule(mode = FilterMode.WHITELIST)
		sortPipe.filterFor(Direction.SOUTH).insert(ItemResource.of(ItemStack(Items.DIAMOND)), 1, false)

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
		setBlock(providerHookPos, BlockRegistry.Hook.defaultBlockState())
		setBlock(requesterHookPos, BlockRegistry.Hook.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val provider = getBlockEntity(providerHookPos) as HookBlockEntity
		provider.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		provider.hooks.getOrPut(Direction.NORTH.name) { ProviderHookType.createState() }

		val requester = getBlockEntity(requesterHookPos) as HookBlockEntity
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
		setBlock(extractorPos, BlockRegistry.Hook.defaultBlockState())
		setBlock(defaultHookPos, BlockRegistry.Hook.defaultBlockState())
		setBlock(defaultDestPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val extractor = getBlockEntity(extractorPos) as HookBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }

		val defaultHook = getBlockEntity(defaultHookPos) as HookBlockEntity
		defaultHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val defaultState = defaultHook.hooks.getOrPut(Direction.SOUTH.name) { SortingHookType.createState() } as SortingHookState
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
		setBlock(extractorPos, BlockRegistry.Hook.defaultBlockState())
		setBlock(midPos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(ordinaryDestPos, Blocks.CHEST.defaultBlockState())
		setBlock(defaultHookPos, BlockRegistry.Hook.defaultBlockState())
		setBlock(defaultDestPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val extractor = getBlockEntity(extractorPos) as HookBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }

		val defaultHook = getBlockEntity(defaultHookPos) as HookBlockEntity
		defaultHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val defaultState = defaultHook.hooks.getOrPut(Direction.EAST.name) { SortingHookType.createState() } as SortingHookState
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
		setBlock(hookAPos, BlockRegistry.Hook.defaultBlockState())
		setBlock(hookBPos, BlockRegistry.Hook.defaultBlockState())

		val hookA = getBlockEntity(hookAPos) as HookBlockEntity
		hookA.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val stateA = hookA.hooks.getOrPut(Direction.NORTH.name) { SortingHookType.createState() } as SortingHookState
		stateA.routing = RoutingModule(mode = FilterMode.BLACKLIST, priority = RoutingModule.DEFAULT_ROUTE_PRIORITY)

		val hookB = getBlockEntity(hookBPos) as HookBlockEntity
		hookB.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		hookB.hooks.getOrPut(Direction.SOUTH.name) { SortingHookType.createState() }

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
}
