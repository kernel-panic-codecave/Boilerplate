package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.pipe.entity.FilterMode
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.RoutingModule
import net.kernelpanicsoft.tubularstorage.pipe.hook.ExtractionHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.FilterHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.InterfaceHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.InterfaceHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.ProviderHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.ProviderHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.RequesterHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.RequesterHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.SyncHookType
import net.kernelpanicsoft.tubularstorage.pipe.network.PipeNetworkManager
import net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity

/**
 * GameTest coverage for the subnet-boundary system (`docs/design/m2-sorting-routing.md`): a hook
 * facing directly into an [InterfaceHookType] hook keeps the two sides' pipe networks logically
 * separate, with the *other* hook's own type determining how the junction behaves - see
 * [net.kernelpanicsoft.tubularstorage.pipe.network.SubnetBoundary].
 */
@Suppress("unused")
class SubnetBoundaryGameTest {
	private fun GameTestHelper.hookAt(pos: BlockPos): MultipartBlockEntity {
		setBlock(pos, BlockRegistry.Multipart.defaultBlockState())
		val tile = getBlockEntity(pos) as MultipartBlockEntity
		tile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		return tile
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testHookFacingInterfaceKeepsNetworksSeparate() {
		val providerPos = BlockPos(0, 2, 0)
		val interfacePos = BlockPos(0, 2, 1)
		hookAt(providerPos).hooks.getOrPut(Direction.SOUTH.name) { ProviderHookType.createState() }
		hookAt(interfacePos).hooks.getOrPut(Direction.NORTH.name) { InterfaceHookType.createState() }

		runAfterDelay(2) {
			val manager = PipeNetworkManager.get(level as ServerLevel)
			val providerNetwork = manager.networkIdAt(absolutePos(providerPos))
			val interfaceNetwork = manager.networkIdAt(absolutePos(interfacePos))
			assertTrue(providerNetwork != null && interfaceNetwork != null && providerNetwork != interfaceNetwork) {
				"Expected the provider and interface hooks to end up on separate networks, got $providerNetwork / $interfaceNetwork"
			}
			succeed()
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testProviderFacingInterfaceCreatesExtractOnlyBoundary() {
		val providerPos = BlockPos(0, 2, 0)
		val requesterPos = BlockPos(1, 2, 0)
		val interfacePos = BlockPos(0, 2, 1)
		// North of the requester, not south - south would also touch interfacePos (a diagonal
		// corner of this compact layout), which is a legal target for the interface's own
		// self-push (InterfaceHookType.tick) too, muddying which mechanism delivered what. Staying
		// tight (a single hop each way) also matters on its own: RequesterHookType.tryRequest has
		// no in-flight-request tracking, so a delivery slower than REQUEST_INTERVAL_TICKS lets a
		// second periodic check re-request the same shortfall before the first arrives - confirmed
		// the hard way with a wider layout (three hops) double-delivering.
		val destPos = BlockPos(1, 2, -1)
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		val provider = hookAt(providerPos)
		val providerState = provider.hooks.getOrPut(Direction.SOUTH.name) { ProviderHookType.createState() } as ProviderHookState
		providerState.routing = RoutingModule(mode = FilterMode.BLACKLIST)

		val requester = hookAt(requesterPos)
		val requesterState = requester.hooks.getOrPut(Direction.NORTH.name) { RequesterHookType.createState() } as RequesterHookState
		requesterState.request.insert(ItemResource.of(ItemStack(Items.DIAMOND)), 5, false)
		placeCreativePressureSource(requesterPos.above())

		val interfaceTile = hookAt(interfacePos)
		val interfaceState = interfaceTile.hooks.getOrPut(Direction.NORTH.name) { InterfaceHookType.createState() } as InterfaceHookState
		interfaceState.stock.insert(ItemResource.of(ItemStack(Items.DIAMOND)), 10, false)

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.DIAMOND) && dest.getItem(0).count == 5) {
				"Expected 5 diamonds pulled from the interface (via the provider) to have arrived, got ${dest.getItem(0)}"
			}
			assertTrue(interfaceState.stock.getAmount(0) == 5L) {
				"Expected the interface's own stock to have dropped by 5, got ${interfaceState.stock.getAmount(0)}"
			}
		}
	}

	/** [InterfaceHookType.tick] proactively pushes whatever lands in [InterfaceHookState.stock] into the network, unprompted - a hopper (or a player) dropping items in behaves like an [ExtractionHookType] sitting on a chest, not a dead end. */
	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testInterfaceSelfPushesStockIntoTheNetwork() {
		val interfacePos = BlockPos(0, 2, 0)
		val destPos = BlockPos(0, 2, 1)
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		val interfaceTile = hookAt(interfacePos)
		val interfaceState = interfaceTile.hooks.getOrPut(Direction.SOUTH.name) { InterfaceHookType.createState() } as InterfaceHookState
		interfaceState.stock.insert(ItemResource.of(ItemStack(Items.DIAMOND)), 4, false)
		placeCreativePressureSource(interfacePos.above())

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.DIAMOND) && dest.getItem(0).count == 4 && interfaceState.stock.getAmount(0) == 0L) {
				"Expected the interface to have self-pushed all 4 diamonds out on its own, got dest=${dest.getItem(0)} interfaceStock=${interfaceState.stock.getAmount(0)}"
			}
		}
	}

	/** The whole point of [InterfaceHookType.providesItems] - an interface's own stock, reachable across the very boundary it anchors, shows up as a pullable source the same way an ordinary [ProviderHookType]-tagged chest would. */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testInterfaceStockIsAValidProviderSourceAcrossTheBoundary() {
		val providerPos = BlockPos(0, 2, 0)
		val interfacePos = BlockPos(0, 2, 1)

		val provider = hookAt(providerPos)
		provider.hooks.getOrPut(Direction.SOUTH.name) { ProviderHookType.createState() }

		val interfaceTile = hookAt(interfacePos)
		val interfaceState = interfaceTile.hooks.getOrPut(Direction.NORTH.name) { InterfaceHookType.createState() } as InterfaceHookState
		interfaceState.stock.insert(ItemResource.of(ItemStack(Items.DIAMOND)), 7, false)

		runAfterDelay(2) {
			val sources = RequestFulfillment.reachableProviders(level as ServerLevel, absolutePos(providerPos))
			val interfaceSource = sources.firstOrNull { it.hookState === interfaceState }
			assertTrue(interfaceSource != null) { "Expected the interface's own hook to register as a reachable provider source, got $sources" }
			assertTrue(interfaceSource!!.storage(level as ServerLevel)?.getAmount(0) == 7L) {
				"Expected the interface source's own storage() to read its stock directly, got ${interfaceSource.storage(level as ServerLevel)}"
			}
			succeed()
		}
	}

	/**
	 * A resource sitting *beyond* the interface - reachable from it via ordinary network-B
	 * topology, not through the boundary itself - must stay invisible to a network-A request. Only
	 * what the interface's own [InterfaceHookState.stock] directly exposes crosses the seam; the
	 * rest of network B does not (`docs/design/m2-sorting-routing.md`'s subnet boundary section).
	 */
	@GameTest(template = SMALL, timeoutTicks = 100)
	fun GameTestHelper.testResourcesBeyondInterfaceStayUnreachableFromOuterNetwork() {
		val providerPos = BlockPos(0, 2, 0)
		val requesterPos = BlockPos(1, 2, 0)
		val interfacePos = BlockPos(0, 2, 1)
		val destPos = BlockPos(1, 2, 1)
		val deeperProviderPos = BlockPos(0, 2, 2)
		val deeperSourcePos = BlockPos(0, 2, 3)
		setBlock(destPos, Blocks.CHEST.defaultBlockState())
		setBlock(deeperSourcePos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(deeperSourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.GOLD_INGOT, 8))

		val provider = hookAt(providerPos)
		val providerState = provider.hooks.getOrPut(Direction.SOUTH.name) { ProviderHookType.createState() } as ProviderHookState
		providerState.routing = RoutingModule(mode = FilterMode.BLACKLIST)

		val requester = hookAt(requesterPos)
		val requesterState = requester.hooks.getOrPut(Direction.SOUTH.name) { RequesterHookType.createState() } as RequesterHookState
		requesterState.request.insert(ItemResource.of(ItemStack(Items.GOLD_INGOT)), 5, false)

		hookAt(interfacePos).hooks.getOrPut(Direction.NORTH.name) { InterfaceHookType.createState() }

		// deeperProviderPos sits on network B's own ordinary topology (adjacent to interfacePos via
		// a ordinary pipe connection, not a boundary edge), exposing gold - not through the
		// interface's own stock at all.
		val deeperProvider = hookAt(deeperProviderPos)
		val deeperProviderState = deeperProvider.hooks.getOrPut(Direction.SOUTH.name) { ProviderHookType.createState() } as ProviderHookState
		deeperProviderState.routing = RoutingModule(mode = FilterMode.BLACKLIST)

		runAfterDelay(100) {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).isEmpty) {
				"Expected gold beyond the interface to stay unreachable from the outer network, got ${dest.getItem(0)}"
			}
			succeed()
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testFilterFacingInterfaceCreatesInsertOnlyBoundary() {
		val sourcePos = BlockPos(0, 2, 1)
		val extractorPos = BlockPos(0, 2, 0)
		val filterPos = BlockPos(1, 2, 0)
		val interfacePos = BlockPos(1, 2, 1)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 5))

		val extractor = hookAt(extractorPos)
		extractor.hooks.getOrPut(Direction.SOUTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		val filter = hookAt(filterPos)
		val filterState = filter.hooks.getOrPut(Direction.SOUTH.name) { FilterHookType.createState() } as SortingHookState
		filterState.routing = RoutingModule(mode = FilterMode.BLACKLIST)

		val interfaceTile = hookAt(interfacePos)
		val interfaceState = interfaceTile.hooks.getOrPut(Direction.NORTH.name) { InterfaceHookType.createState() } as InterfaceHookState
		// A separate source: the interface hook now anchors a pressure-network boundary too (see
		// PressureNetworkBoundary), same as the item-network subnet split it already anchors - its
		// own side needs its own reachable pressure, not a share of the extractor's.
		placeCreativePressureSource(interfacePos.above())

		succeedWhen {
			assertTrue(interfaceState.stock.getAmount(0) == 5L && interfaceState.stock.getResource(0) == ItemResource.of(ItemStack(Items.DIAMOND))) {
				"Expected 5 diamonds pushed through the filter hook to have landed in the interface's stock, got amount ${interfaceState.stock.getAmount(0)} resource ${interfaceState.stock.getResource(0)}"
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testSyncFacingInterfaceCreatesFilteredTwoWayBoundary() {
		val sourcePos = BlockPos(0, 2, 1)
		val extractorPos = BlockPos(0, 2, 0)
		val syncPos = BlockPos(1, 2, 0)
		val requesterPos = BlockPos(2, 2, 0)
		val destPos = BlockPos(2, 2, 1)
		val interfacePos = BlockPos(1, 2, 1)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 5))

		val extractor = hookAt(extractorPos)
		extractor.hooks.getOrPut(Direction.SOUTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		val sync = hookAt(syncPos)
		val syncState = sync.hooks.getOrPut(Direction.SOUTH.name) { SyncHookType.createState() } as SortingHookState
		syncState.routing = RoutingModule(mode = FilterMode.BLACKLIST)

		val requester = hookAt(requesterPos)
		val requesterState = requester.hooks.getOrPut(Direction.SOUTH.name) { RequesterHookType.createState() } as RequesterHookState
		requesterState.request.insert(ItemResource.of(ItemStack(Items.GOLD_INGOT)), 3, false)

		val interfaceTile = hookAt(interfacePos)
		val interfaceState = interfaceTile.hooks.getOrPut(Direction.NORTH.name) { InterfaceHookType.createState() } as InterfaceHookState
		interfaceState.stock.insert(ItemResource.of(ItemStack(Items.GOLD_INGOT)), 3, false)
		// A separate source, same reasoning as testFilterFacingInterfaceCreatesInsertOnlyBoundary's own.
		placeCreativePressureSource(interfacePos.above())

		succeedWhen {
			// Insert half: the extractor's diamonds should have crossed into the interface's stock.
			val diamond = ItemResource.of(ItemStack(Items.DIAMOND))
			val gold = ItemResource.of(ItemStack(Items.GOLD_INGOT))
			val diamondsInInterface = (0 until interfaceState.stock.size())
				.firstOrNull { interfaceState.stock.getResource(it) == diamond }
				?.let { interfaceState.stock.getAmount(it) } ?: 0L
			assertTrue(diamondsInInterface == 5L) {
				"Expected 5 diamonds pushed through the sync hook to have landed in the interface's stock, got $diamondsInInterface"
			}

			// Extract half: the requester's gold order should have been pulled from the interface.
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.GOLD_INGOT) && dest.getItem(0).count == 3) {
				"Expected 3 gold pulled from the interface (via the sync hook) to have arrived, got ${dest.getItem(0)}"
			}
			val goldRemaining = (0 until interfaceState.stock.size())
				.firstOrNull { interfaceState.stock.getResource(it) == gold }
				?.let { interfaceState.stock.getAmount(it) } ?: 0L
			assertTrue(goldRemaining == 0L) { "Expected the interface's gold to have been fully drained, got $goldRemaining" }
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testExtractionHookPullsDirectlyFromInterface() {
		val interfacePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val destPos = BlockPos(0, 2, 2)
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		val interfaceTile = hookAt(interfacePos)
		val interfaceState = interfaceTile.hooks.getOrPut(Direction.SOUTH.name) { InterfaceHookType.createState() } as InterfaceHookState
		interfaceState.stock.insert(ItemResource.of(ItemStack(Items.EMERALD)), 8, false)

		val extractor = hookAt(extractorPos)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.EMERALD) && dest.getItem(0).count == 8) {
				"Expected the extraction hook to have pulled all 8 emeralds directly out of the interface, got ${dest.getItem(0)}"
			}
			assertTrue(interfaceState.stock.getAmount(0) == 0L) { "Expected the interface's own stock to be empty afterward, got ${interfaceState.stock.getAmount(0)}" }
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testRequesterHookSuppliesInterface() {
		val interfacePos = BlockPos(0, 2, 0)
		val requesterPos = BlockPos(0, 2, 1)
		val sourcePos = BlockPos(1, 2, 1)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.NETHERITE_INGOT, 63))

		val interfaceTile = hookAt(interfacePos)
		val interfaceState = interfaceTile.hooks.getOrPut(Direction.SOUTH.name) { InterfaceHookType.createState() } as InterfaceHookState
		// Seed one netherite ingot so the slot has an established target (a full stack) to top up -
		// see InterfaceHookState's own KDoc for why an empty interface has nothing to restock.
		interfaceState.stock.insert(ItemResource.of(ItemStack(Items.NETHERITE_INGOT)), 1, false)

		val requester = hookAt(requesterPos)
		requester.hooks.getOrPut(Direction.NORTH.name) { RequesterHookType.createState() }
		// A provider on the requester's own tile, facing sourcePos, gives RequestFulfillment
		// something to actually pull the shortfall from - a plain chest by itself isn't a source
		// until something opts it into being one (`docs/design/m3-warehouse-storage.md`).
		val providerState = requester.hooks.getOrPut(Direction.EAST.name) { ProviderHookType.createState() } as ProviderHookState
		providerState.routing = RoutingModule(mode = FilterMode.BLACKLIST)
		placeCreativePressureSource(requesterPos.above())

		succeedWhen {
			val source = getBlockEntity(sourcePos) as ChestBlockEntity
			assertTrue(source.getItem(0).isEmpty || source.getItem(0).count < 63) {
				"Expected the requester to have started pulling netherite ingots from its own source to supply the interface, got ${source.getItem(0)}"
			}
			assertTrue(interfaceState.stock.getAmount(0) == 64L) {
				"Expected the interface's netherite slot to have been topped up to a full stack (64), got ${interfaceState.stock.getAmount(0)}"
			}
		}
	}
}
