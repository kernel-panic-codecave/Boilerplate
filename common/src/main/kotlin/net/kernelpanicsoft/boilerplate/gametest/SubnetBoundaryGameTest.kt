package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.FilterHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.ProviderHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.ProviderHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.SyncHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.ItemConditionState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.TagConditionState
import net.kernelpanicsoft.boilerplate.pipe.network.PipeNetworkManager
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity

/**
 * GameTest coverage for the subnet-boundary system (`docs/design/m2-sorting-routing.md`): a hook
 * facing directly into an [InterfaceHookType] hook keeps the two sides' pipe networks logically
 * separate, with the *other* hook's own type determining how the junction behaves - see
 * [net.kernelpanicsoft.boilerplate.pipe.network.SubnetBoundary].
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
		requesterState.target(ItemResource.of(ItemStack(Items.DIAMOND)), 5)
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

	/**
	 * [InterfaceHookType.drainExcess] pushes whatever sits in [InterfaceHookState.stock] above its
	 * target outward, unprompted - an un-ghosted column is pure excess, so a hopper (or a player)
	 * dropping items into an un-ghosted interface behaves like an [ExtractionHookType] sitting on a
	 * chest, not a dead end.
	 *
	 * The geometry is load-bearing: the destination sits on a face the hook is **not** on. An
	 * interface drains *into its own network*, never out through its own face - across the boundary
	 * it is a passive seam that other hooks read from and write to, not something that pushes
	 * (`m2-sorting-routing.md`, "Hook taxonomy"). Routing enforces that on its own: a candidate
	 * reached through a face carrying a `validRoute = false` hook is rejected, and
	 * [InterfaceHookType] is one. Putting the chest behind the hooked face instead tests the
	 * opposite of the design and can only pass by weakening boundary isolation for every hook that
	 * defaults to `validRoute = false`.
	 */
	@GameTest(template = SMALL, timeoutTicks = 80)
	fun GameTestHelper.testInterfaceSelfPushesStockIntoTheNetwork() {
		val interfacePos = BlockPos(0, 2, 0)
		// East of the interface - its own side. The hook faces south, into the seam.
		val destPos = BlockPos(1, 2, 0)
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		val interfaceTile = hookAt(interfacePos)
		val interfaceState = interfaceTile.hooks.getOrPut(Direction.SOUTH.name) { InterfaceHookType.createState() } as InterfaceHookState
		interfaceState.stock.insert(ItemResource.of(ItemStack(Items.DIAMOND)), 4, false)
		placeCreativePressureSource(interfacePos.above())

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.DIAMOND) && dest.getItem(0).count == 4 && interfaceState.stock.getAmount(0) == 0L) {
				"Expected the interface to have drained all 4 diamonds (excess over a blank ghost target) out on its own, got dest=${dest.getItem(0)} interfaceStock=${interfaceState.stock.getAmount(0)}"
			}
		}
	}

	/** The whole point of [InterfaceHookType.providesItems] - an interface's own stock, reachable across the very boundary it anchors, shows up as a pullable source the same way an ordinary [ProviderHookType]-tagged chest would. */
	@GameTest(template = SMALL, timeoutTicks = 60)
	fun GameTestHelper.testInterfaceStockIsAValidProviderSourceAcrossTheBoundary() {
		val providerPos = BlockPos(0, 2, 0)
		val interfacePos = BlockPos(0, 2, 1)

		val provider = hookAt(providerPos)
		provider.hooks.getOrPut(Direction.SOUTH.name) { ProviderHookType.createState() }

		val interfaceTile = hookAt(interfacePos)
		val interfaceState = interfaceTile.hooks.getOrPut(Direction.NORTH.name) { InterfaceHookType.createState() } as InterfaceHookState
		interfaceState.stock.insert(ItemResource.of(ItemStack(Items.DIAMOND)), 7, false)

		succeedWhen {
			val sources = RequestFulfillment.reachableProviders(level as ServerLevel, absolutePos(providerPos))
			val interfaceSource = sources.firstOrNull { it.hookState === interfaceState }
			assertTrue(interfaceSource != null) { "Expected the interface's own hook to register as a reachable provider source, got $sources" }
			assertTrue(interfaceSource!!.storage(level as ServerLevel, ResourceKindRegistry.Item)?.getAmount(0) == 7L) {
				"Expected the interface source's own storage() to read its stock directly, got ${interfaceSource.storage(level as ServerLevel, ResourceKindRegistry.Item)}"
			}
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
		requesterState.target(ItemResource.of(ItemStack(Items.GOLD_INGOT)), 5)

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
		val midPos = BlockPos(1, 2, 0)
		val filterPos = BlockPos(2, 2, 0)
		val interfacePos = BlockPos(2, 2, 1)
		// The interface's pass-through pushes towards *any* accepting inventory on its own subnet
		// side - so the destination sits out of reach of the source chest, or what came across the
		// boundary would happily route right back to where the extractor pulled it from (a 1-hop
		// cycle). Source is west, subnet destination east: the seam they meet at is the interface.
		val subnetPipePos = BlockPos(3, 2, 1)
		val subnetDestPos = BlockPos(4, 2, 1)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(subnetDestPos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 5))

		val extractor = hookAt(extractorPos)
		extractor.hooks.getOrPut(Direction.SOUTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		hookAt(midPos)

		val filter = hookAt(filterPos)
		val filterState = filter.hooks.getOrPut(Direction.SOUTH.name) { FilterHookType.createState() } as SortingHookState
		filterState.routing = RoutingModule(mode = FilterMode.BLACKLIST)

		val interfaceTile = hookAt(interfacePos)
		val interfaceState = interfaceTile.hooks.getOrPut(Direction.NORTH.name) { InterfaceHookType.createState() } as InterfaceHookState
		hookAt(subnetPipePos)
		// A separate source: the interface hook now anchors a pressure-network boundary too (see
		// PressureNetworkBoundary), same as the item-network subnet split it already anchors - its
		// own side needs its own reachable pressure, not a share of the extractor's.
		placeCreativePressureSource(interfacePos.above())

		succeedWhen {
			val dest = getBlockEntity(subnetDestPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.DIAMOND) && dest.getItem(0).count == 5) {
				"Expected 5 diamonds pushed through the filter hook to have passed straight through the interface into the subnet, got ${dest.getItem(0)}"
			}
			assertTrue(
				(0 until interfaceState.stock.size()).none { !interfaceState.stock.getResource(it).isBlank }
			) {
				"Expected the interface's stock to have stayed empty (pass-through, not staging), got resource0=${interfaceState.stock.getResource(0)}"
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testSyncFacingInterfaceCreatesFilteredTwoWayBoundary() {
		val sourcePos = BlockPos(0, 2, -1)
		val extractorPos = BlockPos(0, 2, 0)
		val syncPos = BlockPos(1, 2, 0)
		val requesterPos = BlockPos(2, 2, 0)
		val destPos = BlockPos(2, 2, 1)
		val interfacePos = BlockPos(1, 2, 1)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 5))

		val extractor = hookAt(extractorPos)
		// Source moved north so it's never a pass-through candidate from the interface (an adjacent
		// accepting inventory is one - routing back to it would cycle); the interface's only
		// non-boundary accepting neighbor is the dest chest to the east.
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		val sync = hookAt(syncPos)
		val syncState = sync.hooks.getOrPut(Direction.SOUTH.name) { SyncHookType.createState() } as SortingHookState
		syncState.routing = RoutingModule(mode = FilterMode.BLACKLIST)

		val requester = hookAt(requesterPos)
		val requesterState = requester.hooks.getOrPut(Direction.SOUTH.name) { RequesterHookType.createState() } as RequesterHookState
		requesterState.target(ItemResource.of(ItemStack(Items.GOLD_INGOT)), 3)

		val interfaceTile = hookAt(interfacePos)
		val interfaceState = interfaceTile.hooks.getOrPut(Direction.NORTH.name) { InterfaceHookType.createState() } as InterfaceHookState
		interfaceState.stock.insert(ItemResource.of(ItemStack(Items.GOLD_INGOT)), 3, false)
		// Ghost the gold so the interface neither drains it as excess nor refills it past the order.
		interfaceState.target(ItemResource.of(ItemStack(Items.GOLD_INGOT)), 3)
		// A separate source, same reasoning as testFilterFacingInterfaceCreatesInsertOnlyBoundary's own.
		placeCreativePressureSource(interfacePos.above())

		succeedWhen {
			// Insert half: the extractor's diamonds should have crossed the seam straight through
			// the interface into the subnet's own destination chest (pass-through, not staging).
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			val diamondCount = (0 until dest.containerSize).sumOf { i ->
				if (dest.getItem(i).`is`(Items.DIAMOND)) dest.getItem(i).count else 0
			}
			assertTrue(diamondCount == 5) {
				"Expected 5 diamonds pushed through the sync hook to have passed straight through the interface into the subnet, got $diamondCount"
			}
			assertTrue(
				(0 until interfaceState.stock.size()).none { interfaceState.stock.getResource(it) == ItemResource.of(ItemStack(Items.DIAMOND)) }
			) {
				"Expected the interface's stock to hold no diamonds (pass-through, not staging)"
			}

			// Extract half: the requester's gold order should have been pulled from the interface,
			// through the sync hook's own boundary face.
			val goldCount = (0 until dest.containerSize).sumOf { i ->
				if (dest.getItem(i).`is`(Items.GOLD_INGOT)) dest.getItem(i).count else 0
			}
			assertTrue(goldCount == 3) {
				"Expected 3 gold pulled from the interface (via the sync hook) to have arrived, got $goldCount"
			}
			val goldRemaining = (0 until interfaceState.stock.size())
				.firstOrNull { interfaceState.stock.getResource(it) == ItemResource.of(ItemStack(Items.GOLD_INGOT)) }
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
		// Ghost the emeralds: the interface's own excess-drain would otherwise push them to the same
		// destination, competing with the extraction hook and making "exactly 8 arrived" a race. A
		// correct-before-ghost target makes drain a no-op and leaves extraction as the sole mover.
		interfaceState.target(ItemResource.of(ItemStack(Items.EMERALD)), 8)

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
		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.NETHERITE_INGOT, 64))

		val interfaceTile = hookAt(interfacePos)
		val interfaceState = interfaceTile.hooks.getOrPut(Direction.SOUTH.name) { InterfaceHookType.createState() } as InterfaceHookState
		// Deliberately left with no targets of its own. A requester facing an interface is what
		// decides that boundary's contents (see RequesterHookType), and this test is what pins that:
		// the stocking below happens entirely off the requester's row.

		val requester = hookAt(requesterPos)
		val requesterState = requester.hooks.getOrPut(Direction.NORTH.name) { RequesterHookType.createState() } as RequesterHookState
		requesterState.target(ItemResource.of(ItemStack(Items.NETHERITE_INGOT)), 64)
		// A provider on the requester's own tile, facing sourcePos, gives RequestFulfillment
		// something to actually pull the shortfall from - a plain chest by itself isn't a source
		// until something opts it into being one (`docs/design/m3-warehouse-storage.md`).
		val providerState = requester.hooks.getOrPut(Direction.EAST.name) { ProviderHookType.createState() } as ProviderHookState
		providerState.routing = RoutingModule(mode = FilterMode.BLACKLIST)
		placeCreativePressureSource(requesterPos.above())

		succeedWhen {
			val source = getBlockEntity(sourcePos) as ChestBlockEntity
			assertTrue(source.getItem(0).isEmpty || source.getItem(0).count < 64) {
				"Expected the requester to have started pulling netherite ingots from its own source to supply the interface, got ${source.getItem(0)}"
			}
			assertTrue(interfaceState.stock.getAmount(0) == 64L) {
				"Expected the interface's netherite slot to have been topped up to the requester's own target (64), got ${interfaceState.stock.getAmount(0)}"
			}
		}
	}

	/** A machine (or hopper, or another mod's pipe) inserting into the interface's exposed face is routed straight into the network - the [InterfaceHookState.exposedItemStorage] pass-through surface - rather than staged in [InterfaceHookState.stock]. Exercises both write routes: the whole-storage resource insert and the per-slot write that CSL's NeoForge `IItemHandler` bridge (what cross-mod machines like Create/Pipez actually drive items through) uses. */
	@GameTest(template = SMALL, timeoutTicks = 120)
	fun GameTestHelper.testMachinePushPassesStraightThroughInterface() {
		val interfacePos = BlockPos(0, 2, 0)
		val pipePos = BlockPos(0, 2, 1)
		val destPos = BlockPos(0, 2, 2)
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		val interfaceTile = hookAt(interfacePos)
		val interfaceState = interfaceTile.hooks.getOrPut(Direction.SOUTH.name) { InterfaceHookType.createState() } as InterfaceHookState
		hookAt(pipePos)

		var accepted = 0L
		var slotAccepted = 0L
		succeedWhen {
			// The network manager only registers freshly placed pipes at their first server tick, and
			// under concurrent gametest load that can land late; draining one insert attempt per poll
			// until it is actually accepted makes the test robust to registration timing instead of
			// freezing a single runAfterDelay insert at a rejected 0.
			if (accepted == 0L) {
				accepted = interfaceState.exposedItemStorage(interfaceTile)!!
					.insert(ItemResource.of(ItemStack(Items.EMERALD)), 5, false)
			}
			if (slotAccepted == 0L) {
				slotAccepted = interfaceState.exposedItemStorage(interfaceTile)!!
					.get(0).insert(ItemResource.of(ItemStack(Items.DIAMOND)), 7, false)
			}
			assertTrue(accepted == 5L) { "Expected the machine's storage insert to have been fully accepted, got $accepted" }
			assertTrue(slotAccepted == 7L) { "Expected the machine's slot insert to have been fully accepted, got $slotAccepted" }
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			var emeralds = 0
			var diamonds = 0
			for (i in 0 until dest.containerSize) {
				val stack = dest.getItem(i)
				if (stack.`is`(Items.EMERALD)) emeralds += stack.count
				if (stack.`is`(Items.DIAMOND)) diamonds += stack.count
			}
			assertTrue(emeralds == 5) { "Expected the 5 emeralds to have passed straight through into the network destination, got $emeralds" }
			assertTrue(diamonds == 7) { "Expected the 7 diamonds (slot-written) to have passed straight through into the network destination, got $diamonds" }
			assertTrue(
				(0 until interfaceState.stock.size()).none { !interfaceState.stock.getResource(it).isBlank }
			) {
				"Expected the interface's stock to have stayed empty (pass-through, not staging), got resource0=${interfaceState.stock.getResource(0)}"
			}
		}
	}

	/** The other half of the pass-through contract: an insert with nowhere accepting to route to is rejected (returns `0`), not staged. */
	@GameTest(template = SMALL, timeoutTicks = 60)
	fun GameTestHelper.testMachinePushRejectedWhenNoNetworkRoute() {
		val interfacePos = BlockPos(0, 2, 0)
		val interfaceTile = hookAt(interfacePos)
		val interfaceState = interfaceTile.hooks.getOrPut(Direction.NORTH.name) { InterfaceHookType.createState() } as InterfaceHookState

		val storage = interfaceState.exposedItemStorage(interfaceTile)!!
		val accepted = storage.insert(ItemResource.of(ItemStack(Items.EMERALD)), 5, false)
		val slotAccepted = storage.get(0).insert(ItemResource.of(ItemStack(Items.DIAMOND)), 7, false)

		runAfterDelay(40) {
			assertTrue(accepted == 0L) { "Expected the machine's insert to have been rejected (no accepting destination), got $accepted" }
			assertTrue(slotAccepted == 0L) { "Expected the machine's slot insert to have been rejected (no accepting destination), got $slotAccepted" }
			assertTrue(
				(0 until interfaceState.stock.size()).none { !interfaceState.stock.getResource(it).isBlank }
			) {
				"Expected the interface's stock to have stayed empty after a rejected insert, got resource0=${interfaceState.stock.getResource(0)}"
			}
			succeed()
		}
	}

	/** The pass-through route must never hand items back to the accepting inventory physically feeding the face - the exact setup that has a machine as both the pusher and a best-route one-hop destination. */
	@GameTest(template = SMALL, timeoutTicks = 80)
	fun GameTestHelper.testInterfacePassThroughDoesNotRouteBackIntoItsFeeder() {
		val interfacePos = BlockPos(0, 2, 0)
		val feederPos = BlockPos(0, 2, 1)
		val pipePos = BlockPos(1, 2, 0)
		val destPos = BlockPos(2, 2, 0)
		setBlock(feederPos, Blocks.CHEST.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		val interfaceTile = hookAt(interfacePos)
		val interfaceState = interfaceTile.hooks.getOrPut(Direction.SOUTH.name) { InterfaceHookType.createState() } as InterfaceHookState
		hookAt(pipePos)

		var accepted = 0L
		succeedWhen {
			if (accepted == 0L) {
				accepted = ItemApi.BLOCK.find(level, absolutePos(interfacePos), Direction.SOUTH)!!
					.insert(ItemResource.of(ItemStack(Items.EMERALD)), 5, false)
			}
			assertTrue(accepted == 5L) { "Expected the machine's insert to have been fully accepted, got $accepted" }
			val feeder = getBlockEntity(feederPos) as ChestBlockEntity
			var feederContent = 0
			for (i in 0 until feeder.containerSize) feederContent += feeder.getItem(i).count
			assertTrue(feederContent == 0) { "Expected the 5 emeralds NOT to have been routed back into the feeding chest, got $feederContent in it" }
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			var emeralds = 0
			for (i in 0 until dest.containerSize) {
				val stack = dest.getItem(i)
				if (stack.`is`(Items.EMERALD)) emeralds += stack.count
			}
			assertTrue(emeralds == 5) { "Expected the 5 emeralds to have reached the network destination beyond the interface, got $emeralds" }
		}
	}

	/** A pass-through insert must still respect filter routing: an item inserted through the interface is routed to an inventory gated by a whitelist [FilterHookType] face exactly like one the extractor itself pulled. A colorless insert is color-agnostic, so it even reaches a colored filter (`module.color` only splits colored streams off each other, per `docs/design/m2-sorting-routing.md`). */
	@GameTest(template = SMALL, timeoutTicks = 80)
	fun GameTestHelper.testInterfacePassThroughReachesFilteredDestination() {
		val interfacePos = BlockPos(0, 2, 0)
		val feederPos = BlockPos(0, 2, 1)
		val pipePos = BlockPos(1, 2, 0)
		val filterPipePos = BlockPos(2, 2, 0)
		val destPos = BlockPos(2, 2, 1)
		setBlock(feederPos, Blocks.CHEST.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		val interfaceTile = hookAt(interfacePos)
		val interfaceState = interfaceTile.hooks.getOrPut(Direction.SOUTH.name) { InterfaceHookType.createState() } as InterfaceHookState
		hookAt(pipePos)
		val filterPipe = hookAt(filterPipePos)
		val filterState = filterPipe.hooks.getOrPut(Direction.SOUTH.name) { FilterHookType.createState() } as SortingHookState
		filterState.routing = RoutingModule(mode = FilterMode.WHITELIST, color = DyeColor.RED)
		filterState.filter.insert(ItemResource.of(buildItemCard(ItemStack(Items.EMERALD))), 1, false)

		var accepted = 0L
		succeedWhen {
			if (accepted == 0L) {
				accepted = ItemApi.BLOCK.find(level, absolutePos(interfacePos), Direction.SOUTH)!!
					.insert(ItemResource.of(ItemStack(Items.EMERALD)), 5, false)
			}
			assertTrue(accepted == 5L) { "Expected the machine's insert to have been fully accepted (routed to the whitelisted destination), got $accepted" }
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			var emeralds = 0
			for (i in 0 until dest.containerSize) {
				val stack = dest.getItem(i)
				if (stack.`is`(Items.EMERALD)) emeralds += stack.count
			}
			assertTrue(emeralds == 5) { "Expected the 5 emeralds to have reached the inventory past the whitelist filter, got $emeralds" }
			assertTrue(
				(0 until interfaceState.stock.size()).none { !interfaceState.stock.getResource(it).isBlank }
			) {
				"Expected the interface's stock to have stayed empty (pass-through, not staging), got resource0=${interfaceState.stock.getResource(0)}"
			}
		}
	}

	/** A machine auto-ejecting to an interface above it and a tag-filtered machine input further along the same output line: the pass-through must route the intermediate straight into the next machine, through the tag whitelist card. */
	@GameTest(template = SMALL, timeoutTicks = 80)
	fun GameTestHelper.testMachineOutputRoutesThroughTagFilteredRowToNextMachine() {
		val machineAPos = BlockPos(0, 2, 0)
		val interfaceAPos = BlockPos(0, 3, 0)
		val pipePos = BlockPos(1, 3, 0)
		val filterPipePos = BlockPos(2, 3, 0)
		val machineBPos = BlockPos(2, 2, 0)
		setBlock(machineAPos, Blocks.CHEST.defaultBlockState())
		setBlock(machineBPos, Blocks.CHEST.defaultBlockState())

		val interfaceATile = hookAt(interfaceAPos)
		val interfaceAState = interfaceATile.hooks.getOrPut(Direction.DOWN.name) { InterfaceHookType.createState() } as InterfaceHookState
		hookAt(pipePos)
		val filterPipe = hookAt(filterPipePos)
		val filterState = filterPipe.hooks.getOrPut(Direction.DOWN.name) { FilterHookType.createState() } as SortingHookState
		filterState.routing = RoutingModule(mode = FilterMode.WHITELIST)
		filterState.filter.insert(ItemResource.of(rawMaterialsCard("c:raw_materials")), 1, false)

		var accepted = 0L
		succeedWhen {
			if (accepted == 0L) {
				accepted = ItemApi.BLOCK.find(level, absolutePos(interfaceAPos), Direction.DOWN)!!
					.insert(ItemResource.of(ItemStack(Items.RAW_IRON)), 5, false)
			}
			assertTrue(accepted == 5L) { "Expected the machine's auto-output to have been fully accepted (routed to the next machine's input), got $accepted" }
			val dest = getBlockEntity(machineBPos) as ChestBlockEntity
			var rawIron = 0
			for (i in 0 until dest.containerSize) {
				val stack = dest.getItem(i)
				if (stack.`is`(Items.RAW_IRON)) rawIron += stack.count
			}
			assertTrue(rawIron == 5) { "Expected the 5 raw iron to have reached the next machine's input past the tag whitelist filter, got $rawIron" }
			assertTrue(
				(0 until interfaceAState.stock.size()).none { !interfaceAState.stock.getResource(it).isBlank }
			) {
				"Expected machine A's interface stock to have stayed empty (pass-through, not staging), got resource0=${interfaceAState.stock.getResource(0)}"
			}
		}
	}

	/** A smelting chain (cobble -> stone -> smooth stone): each stage's machine auto-outputs into the interface above it, and the next stage's input is gated by a filter hook on the machine's side, all on one pipe line (interfaces wired together by plain pipe, no subnet seams). An intermediate must go straight into the next stage's input gate, not wander into the next stage's output interface or stall. */
	@GameTest(template = SMALL, timeoutTicks = 120)
	fun GameTestHelper.testSmeltingChainStageOutputRoutesDirectlyIntoNextStageInput() {
		val machine1Pos = BlockPos(0, 2, 0)
		val interface1Pos = BlockPos(0, 3, 0)
		val stage2InputFilterPos = BlockPos(1, 2, 0)
		val machine2Pos = BlockPos(2, 2, 0)
		val interface2Pos = BlockPos(2, 3, 0)
		val pipePos = BlockPos(1, 3, 0)
		setBlock(machine1Pos, Blocks.CHEST.defaultBlockState())
		setBlock(machine2Pos, Blocks.CHEST.defaultBlockState())

		val interface1Tile = hookAt(interface1Pos)
		val interface1State = interface1Tile.hooks.getOrPut(Direction.DOWN.name) { InterfaceHookType.createState() } as InterfaceHookState
		hookAt(pipePos)
		hookAt(interface2Pos).hooks.getOrPut(Direction.DOWN.name) { InterfaceHookType.createState() }
		val stage2Filter = hookAt(stage2InputFilterPos)
		val stage2FilterState = stage2Filter.hooks.getOrPut(Direction.EAST.name) { FilterHookType.createState() } as SortingHookState
		stage2FilterState.routing = RoutingModule(mode = FilterMode.WHITELIST)
		stage2FilterState.filter.insert(ItemResource.of(buildItemCard(ItemStack(Items.STONE))), 1, false)

		var accepted = 0L
		succeedWhen {
			if (accepted == 0L) {
				accepted = ItemApi.BLOCK.find(level, absolutePos(interface1Pos), Direction.DOWN)!!
					.insert(ItemResource.of(ItemStack(Items.STONE)), 5, false)
			}
			assertTrue(accepted == 5L) { "Expected stage 1's auto-output to have been fully accepted (routed into stage 2's stone input gate), got $accepted" }
			val dest = getBlockEntity(machine2Pos) as ChestBlockEntity
			var stones = 0
			for (i in 0 until dest.containerSize) {
				val stack = dest.getItem(i)
				if (stack.`is`(Items.STONE)) stones += stack.count
			}
			assertTrue(stones == 5) { "Expected the 5 stone to have reached stage 2's machine chest through its side filter, got $stones" }
			assertTrue(
				(0 until interface1State.stock.size()).none { !interface1State.stock.getResource(it).isBlank }
			) {
				"Expected stage 1's interface stock to have stayed empty (pass-through, not staging), got resource0=${interface1State.stock.getResource(0)}"
			}
		}
	}

	private fun buildItemCard(stack: ItemStack): ItemStack {
		val itemCard = ItemStack(ItemRegistry.ItemFilterCard)
		FilterCardState(itemCard).apply {
			(currentState() as ItemConditionState).itemMatches[0] = ItemResource.of(stack)
			touchCurrentState()
		}
		return itemCard
	}

	/** [InterfaceHookType.requisitionStock] actively tops [InterfaceHookState.stock] up to its own [InterfaceHookState.ghosts] targets from the network, no external [RequesterHookType] needed. */
	@GameTest(template = SMALL, timeoutTicks = 160)
	fun GameTestHelper.testInterfaceSelfRequisitionsGhostShortfall() {
		val interfacePos = BlockPos(0, 2, 0)
		val providerPipePos = BlockPos(0, 2, 1)
		val sourcePos = BlockPos(0, 2, 2)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.RAW_IRON, 32))

		val interfaceTile = hookAt(interfacePos)
		val interfaceState = interfaceTile.hooks.getOrPut(Direction.SOUTH.name) { InterfaceHookType.createState() } as InterfaceHookState
		interfaceState.target(ItemResource.of(ItemStack(Items.RAW_IRON)), 16)
		placeCreativePressureSource(interfacePos.above())

		val providerPipe = hookAt(providerPipePos)
		val providerState = providerPipe.hooks.getOrPut(Direction.SOUTH.name) { ProviderHookType.createState() } as ProviderHookState
		providerState.routing = RoutingModule(mode = FilterMode.BLACKLIST)
		placeCreativePressureSource(providerPipePos.above())

		succeedWhen {
			val rawIron = ItemResource.of(ItemStack(Items.RAW_IRON))
			val stocked = (0 until interfaceState.stock.size())
				.firstOrNull { interfaceState.stock.getResource(it) == rawIron }
				?.let { interfaceState.stock.getAmount(it) } ?: 0L
			assertTrue(stocked == 16L) {
				"Expected the interface to have self-requisitioned 16 raw iron into its stock, got $stocked"
			}
			assertTrue(interfaceState.targetAmounts[0] == 16L) {
				"Expected the target to have stayed at 16, got ${interfaceState.targetAmounts[0]}"
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testWhitelistTagCardPassesRawIronThroughInterfaceIntoSubnet() = rawIronThroughWhitelistCard("c:raw_materials")

	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testWhitelistWildcardTagCardPassesRawIronThroughInterfaceIntoSubnet() = rawIronThroughWhitelistCard("c:raw_materials/*")

	private fun GameTestHelper.rawIronThroughWhitelistCard(tagId: String): Boolean {
		val sourcePos = BlockPos(0, 2, 1)
		val extractorPos = BlockPos(0, 2, 0)
		val midPos = BlockPos(1, 2, 0)
		val filterPos = BlockPos(2, 2, 0)
		val interfacePos = BlockPos(2, 2, 1)
		// Same west-source/east-subnet seam as testFilterFacingInterfaceCreatesInsertOnlyBoundary:
		// the destination must not be reachable from the interface, or the pass-through would dump
		// the raw iron right back where the extractor pulled it from.
		val subnetPipePos = BlockPos(3, 2, 1)
		val subnetDestPos = BlockPos(4, 2, 1)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(subnetDestPos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(sourcePos) as ChestBlockEntity).setItem(0, ItemStack(Items.RAW_IRON, 5))

		val extractor = hookAt(extractorPos)
		extractor.hooks.getOrPut(Direction.SOUTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		hookAt(midPos)

		val filter = hookAt(filterPos)
		val filterState = filter.hooks.getOrPut(Direction.SOUTH.name) { FilterHookType.createState() } as SortingHookState
		filterState.routing = RoutingModule(mode = FilterMode.WHITELIST)
		filterState.filter.insert(ItemResource.of(rawMaterialsCard(tagId)), 1, false)

		val interfaceTile = hookAt(interfacePos)
		val interfaceState = interfaceTile.hooks.getOrPut(Direction.NORTH.name) { InterfaceHookType.createState() } as InterfaceHookState
		hookAt(subnetPipePos)
		placeCreativePressureSource(interfacePos.above())

		succeedWhen {
			val dest = getBlockEntity(subnetDestPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.RAW_IRON) && dest.getItem(0).count == 5) {
				"Expected 5 raw iron pushed through the whitelist tag card to have passed straight through the interface, got ${dest.getItem(0)}"
			}
			assertTrue(
				(0 until interfaceState.stock.size()).none { !interfaceState.stock.getResource(it).isBlank }
			) {
				"Expected the interface's stock to have stayed empty (pass-through, not staging), got resource0=${interfaceState.stock.getResource(0)}"
			}
		}
		return true
	}

	private fun rawMaterialsCard(tagId: String): ItemStack {
		val card = ItemStack(ItemRegistry.TagFilterCard)
		FilterCardState(card).apply {
			(currentState() as TagConditionState).tagId = tagId
			touchCurrentState()
		}
		return card
	}
}
