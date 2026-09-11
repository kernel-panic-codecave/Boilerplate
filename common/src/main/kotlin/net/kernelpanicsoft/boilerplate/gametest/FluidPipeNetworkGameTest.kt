package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.resources.fluid.util.FluidAmounts
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.FilterHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.ResourceConditionState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.ModConditionState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.RegexConditionState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.TagConditionState
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.registry.ResourceKindRegistry
import net.kernelpanicsoft.boilerplate.pipe.network.FluidNetworkManager
import net.kernelpanicsoft.boilerplate.pipe.network.FluidNetworkType
import net.kernelpanicsoft.boilerplate.pipe.network.ItemNetworkType
import net.kernelpanicsoft.boilerplate.pipe.network.ResourceNetworkType
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.warehouse.Bounds
import net.kernelpanicsoft.boilerplate.warehouse.entity.WarehouseControllerBlockEntity
import net.kernelpanicsoft.boilerplate.warehouse.tank.FluidTankBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.Fluids

/**
 * GameTest coverage that a plain [BlockRegistry.Pipe] carries the fluid network as well as the item
 * one - a single pipe kind serves both, routing each envelope through whichever
 * [ResourceNetworkType] its resource kind resolves to (see
 * [net.kernelpanicsoft.boilerplate.registry.NetworkTypeRegistry] / [PipeBlockEntity.tick]) - and
 * that the kind-parametric extraction path is wired to every registered carrier rather than to
 * items specifically.
 */
@Suppress("unused")
class FluidPipeNetworkGameTest {
	@GameTest(template = SMALL)
	fun GameTestHelper.testPlainPipesMergeIntoOneFluidNetwork() {
		val posA = BlockPos(0, 2, 0)
		val posB = BlockPos(0, 2, 1)
		setBlock(posA, BlockRegistry.Pipe.defaultBlockState())
		setBlock(posB, BlockRegistry.Pipe.defaultBlockState())

		val serverLevel = level as ServerLevel
		val a = absolutePos(posA)
		val b = absolutePos(posB)
		val manager = FluidNetworkManager.get(serverLevel)
		manager.ensureRegistered(serverLevel, a)
		manager.ensureRegistered(serverLevel, b)

		val networkA = manager.networkIdAt(a)
		assertTrue(networkA != null) { "Expected a plain pipe to be a member of the fluid network, got null" }
		assertTrue(networkA == manager.networkIdAt(b)) { "Expected adjacent plain pipes to share one fluid network" }
		succeed()
	}

	/**
	 * The generified [ExtractionHookType] is attachable on every registered *carrier* network, not
	 * just the item one - the contract that lets one hook item pull items from a chest and fluids
	 * from a tank. Asserted against the live registry rather than a literal set, since deriving it
	 * is the whole point: an addon's own carrier kind has to land here with no edit to Boilerplate.
	 */
	@GameTest(template = SMALL)
	fun GameTestHelper.testExtractionHookIsAttachableOnEveryCarrierNetwork() {
		val compatible = ExtractionHookType.compatibleNetworkTypes
		assertTrue(ItemNetworkType in compatible) { "Expected the extraction hook to stay attachable on item pipes, got $compatible" }
		assertTrue(FluidNetworkType in compatible) { "Expected the extraction hook to be attachable on fluid pipes, got $compatible" }
		assertTrue(compatible.all { it is ResourceNetworkType<*> }) {
			"Expected only carrier networks to be attachable - pressure conducts but carries nothing - got $compatible"
		}
		succeed()
	}

	/**
	 * Each carrier network brings its own batch size, and the fluid one resolves to a real, non-zero
	 * per-platform volume.
	 *
	 * The non-zero assertion is the point, not pedantry: Common Storage Lib 0.0.5 leaves every
	 * `FluidAmounts` *constant* at `0` on both loaders (its `@Expect`/`@Actual` field linkage drops
	 * the static initializer), and a zero batch makes fluid extraction silently pull nothing while
	 * every other test still passes. This is the regression guard for that - see
	 * [FluidNetworkType.extractionBatch]'s own KDoc.
	 */
	@GameTest(template = SMALL)
	fun GameTestHelper.testEachCarrierNetworkBringsItsOwnExtractionBatch() {
		assertTrue(ItemNetworkType.extractionBatch == 64L) {
			"Expected items to still extract a stack at a time, got ${ItemNetworkType.extractionBatch}"
		}
		val fluidBatch = FluidNetworkType.extractionBatch
		assertTrue(fluidBatch > 0L) {
			"Expected a non-zero fluid batch - a zero batch extracts nothing at all, silently. " +
				"FluidAmounts.BUCKET reads ${FluidAmounts.BUCKET} here, which is why it must not be used directly."
		}
		assertTrue(fluidBatch == FluidAmounts.toPlatformAmount(1000L)) {
			"Expected the fluid batch to be one platform bucket (${FluidAmounts.toPlatformAmount(1000L)}), got $fluidBatch"
		}
		succeed()
	}

	/**
	 * A fluid envelope hops from one plain pipe to the next through the ordinary transport loop -
	 * the whole point of one pipe kind serving both networks. Nothing item-specific is involved:
	 * [net.kernelpanicsoft.boilerplate.pipe.network.networkTypeForResource] resolves the envelope to
	 * [FluidNetworkType], and that type's own `isPipeAt`/`deposit` drive the hop.
	 *
	 * The path deliberately runs one hop past the second pipe so the traveler has somewhere to hop
	 * *to* without this test needing a fluid container, which the mod does not yet have - the
	 * arrival half is what a real tank fixture will cover. What's asserted here is the handoff: the
	 * envelope leaves the first segment and is held, intact and still a fluid, by the second.
	 */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testAFluidEnvelopeHopsBetweenPlainPipes() {
		val firstPos = BlockPos(4, 2, 4)
		val secondPos = BlockPos(4, 2, 5)
		setBlock(firstPos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(secondPos, BlockRegistry.Pipe.defaultBlockState())
		placeCreativePressureSource(firstPos.above())

		val water = FluidResource.of(Fluids.WATER)
		val bucket = FluidAmounts.toPlatformAmount(1000L)
		val first = getBlockEntity(firstPos) as PipeBlockEntity
		val second = getBlockEntity(secondPos) as PipeBlockEntity
		first.travelingItems += TravelingItem(
			ResourceStack(water, bucket),
			Direction.NORTH,
			0f,
			listOf(absolutePos(secondPos), absolutePos(secondPos.south())),
		)

		succeedWhen {
			val carried = second.travelingItems
			assertTrue(carried.size == 1) {
				"Expected the second segment to be holding the fluid, got ${carried.size} travelers " +
					"(first still holds ${first.travelingItems.size})"
			}
			// FluidResource has no value equality in CSL 0.0.5 (unlike ItemResource), so the fluid
			// type is compared rather than the resource object - see the suite's own notes.
			assertTrue(carried[0].stack.resource.let { it is FluidResource && it.isOf(Fluids.WATER) }) {
				"Expected the hop to preserve the water resource, got ${carried[0].stack.resource}"
			}
			assertTrue(carried[0].stack.amount == bucket) {
				"Expected a whole bucket ($bucket) to survive the hop, got ${carried[0].stack.amount}"
			}
		}
	}

	/**
	 * The whole Stage 1 path end to end: a full [FluidTankBlockEntity] on one face of a segment
	 * carrying a plain [ExtractionHookType] hook, a pipe run, and an empty tank at the far end.
	 *
	 * Nothing here is fluid-specific except the two tanks. The hook is the same one that pulls items
	 * from a chest, the pipe is the same plain pipe, and the route is resolved by the same BFS - the
	 * kind is decided entirely by what capability the neighbouring block happens to expose. That is
	 * the point of generifying the hook rather than adding a fluid-specific one.
	 */
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testExtractionHookMovesFluidFromTankToTank() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val destPos = BlockPos(0, 2, 2)
		setBlock(sourcePos, BlockRegistry.FluidTank.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(destPos, BlockRegistry.FluidTank.defaultBlockState())

		val water = FluidResource.of(Fluids.WATER)
		val poured = FluidAmounts.toPlatformAmount(4_000L)
		val source = getBlockEntity(sourcePos) as FluidTankBlockEntity
		assertTrue(source.storage.insert(water, poured, false) == poured) {
			"Expected the source tank to accept ${poured} of water up front"
		}

		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		succeedWhen {
			val dest = getBlockEntity(destPos) as FluidTankBlockEntity
			val arrived = dest.storage.getAmount(0)
			assertTrue(dest.storage.getResource(0).isOf(Fluids.WATER)) {
				"Expected water in the destination tank, got ${dest.storage.getResource(0)}"
			}
			assertTrue(arrived == poured) {
				val inFlight = (getBlockEntity(extractorPos) as MultipartBlockEntity).travelingItems
				"Expected all $poured of the water to have arrived, got $arrived " +
					"(source holds ${source.storage.getAmount(0)}, ${inFlight.size} still in the pipe)"
			}
		}
	}

	/**
	 * A fluid tank standing inside a bound warehouse volume is indexed like any other storage - the
	 * warehouse indexes whatever capability a position exposes, not items specifically.
	 *
	 * This is what forced [net.kernelpanicsoft.boilerplate.resource.ResourceIdentity] to exist:
	 * [net.kernelpanicsoft.boilerplate.warehouse.WarehouseIndex.locations] is a map keyed by
	 * resource, and a raw `FluidResource` key never matches itself on lookup, so the tank would be
	 * scanned, stored, and then be permanently unfindable.
	 */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testWarehouseIndexesAFluidTankInsideItsVolume() {
		val controllerPos = BlockPos(0, 2, 0)
		val tankPos = BlockPos(2, 2, 2)
		val outsideTankPos = BlockPos(5, 2, 5)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(tankPos, BlockRegistry.FluidTank.defaultBlockState())
		setBlock(outsideTankPos, BlockRegistry.FluidTank.defaultBlockState())

		val water = FluidResource.of(Fluids.WATER)
		val lava = FluidResource.of(Fluids.LAVA)
		val stored = FluidAmounts.toPlatformAmount(3_000L)
		(getBlockEntity(tankPos) as FluidTankBlockEntity).storage.insert(water, stored, false)
		(getBlockEntity(outsideTankPos) as FluidTankBlockEntity).storage.insert(lava, stored, false)

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(tankPos))

		succeedWhen {
			val entries = controller.index.slotsFor(water)
			assertTrue(entries.size == 1 && entries[0].amount == stored) {
				"Expected the tank's $stored of water to be indexed once, got $entries"
			}
			assertTrue(entries[0].pos == absolutePos(tankPos)) {
				"Expected the indexed entry to point at the tank, got ${entries[0].pos}"
			}
			assertTrue(controller.index.slotsFor(lava).isEmpty()) {
				"Expected the tank outside the bound volume to not be indexed"
			}
		}
	}

	/**
	 * A fluid is now an ordinary input to a filter card. The kind-agnostic conditions - mod, tag,
	 * regex - judge it directly, which is what makes a sorting hook usable on a fluid line at all:
	 * before this, `FluidPipeRouter.acceptsByFilter` could not evaluate anything and a whitelist
	 * denied every fluid unconditionally.
	 */
	@GameTest(template = SMALL)
	fun GameTestHelper.testKindAgnosticFilterCardsJudgeAFluid() {
		val water = FluidResource.of(Fluids.WATER)
		val lava = FluidResource.of(Fluids.LAVA)

		val byMod = sortingHook(ItemStack(ItemRegistry.ModFilterCard)) { (it as ModConditionState).modId = "minecraft" }
		assertTrue(byMod.accepts(water)) { "Expected a minecraft-namespace mod card to accept water" }

		val byOtherMod = sortingHook(ItemStack(ItemRegistry.ModFilterCard)) { (it as ModConditionState).modId = "create" }
		assertTrue(!byOtherMod.accepts(water)) { "Expected a create-namespace mod card to reject water" }

		val byTag = sortingHook(ItemStack(ItemRegistry.TagFilterCard)) { (it as TagConditionState).tagId = "minecraft:water" }
		assertTrue(byTag.accepts(water)) { "Expected the minecraft:water fluid tag to accept water" }
		assertTrue(!byTag.accepts(lava)) { "Expected the minecraft:water fluid tag to reject lava" }

		val byRegex = sortingHook(ItemStack(ItemRegistry.RegexFilterCard)) { (it as RegexConditionState).regex = "^minecraft:lava$" }
		assertTrue(byRegex.accepts(lava)) { "Expected a regex on the registry id to accept lava" }
		assertTrue(!byRegex.accepts(water)) { "Expected a regex on the registry id to reject water" }
		succeed()
	}

	/**
	 * An item ghost grid cannot express a fluid, so it never *matches* one - and the card's own mode
	 * then decides, exactly as it does for an item that fails to match. A whitelist denies the
	 * fluid; a blacklist passes it.
	 *
	 * This is the fail-safe half of the design: an item-only card must not silently wave fluids
	 * through a whitelist the player set up to keep things out.
	 */
	@GameTest(template = SMALL)
	fun GameTestHelper.testAnItemCardNeverMatchesAFluidAndModeDecides() {
		val water = FluidResource.of(Fluids.WATER)
		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))

		val whitelist = sortingHook(ItemStack(ItemRegistry.ResourceFilterCard), FilterMode.WHITELIST) {
			(it as ResourceConditionState).resourceMatches[0] = diamond
		}
		assertTrue(whitelist.accepts(diamond)) { "Expected the item card to still accept its own item" }
		assertTrue(!whitelist.accepts(water)) { "Expected a whitelist of items to deny a fluid it cannot express" }

		val blacklist = sortingHook(ItemStack(ItemRegistry.ResourceFilterCard), FilterMode.BLACKLIST) {
			(it as ResourceConditionState).resourceMatches[0] = diamond
		}
		assertTrue(!blacklist.accepts(diamond)) { "Expected the blacklist to reject its own item" }
		assertTrue(blacklist.accepts(water)) { "Expected a blacklist of items to pass a fluid it cannot express" }
		succeed()
	}

	/** A detached [SortingHookState] holding one configured filter card - enough to exercise [SortingHookState.accepts] without building a pipe. */
	private fun sortingHook(
		card: ItemStack,
		mode: FilterMode = FilterMode.WHITELIST,
		configure: (net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterConditionState) -> Unit,
	): SortingHookState {
		FilterCardState(card).apply {
			currentState()?.let(configure)
			touchCurrentState()
		}
		val hook = FilterHookType.createState() as SortingHookState
		hook.routing = RoutingModule(mode = mode)
		hook.filter.insert(ItemResource.of(card), 1, false)
		return hook
	}

	/**
	 * [ResourceConditionType] names fluids outright, the way an item card names items - the one card
	 * that can say "this exact fluid" rather than describing it by mod, tag or pattern.
	 *
	 * The blank-slot assertion is not filler: the grid is nine slots of `FluidResource.BLANK`, and
	 * a membership test that forgot to skip blanks would match every fluid in the game.
	 */
	@GameTest(template = SMALL)
	fun GameTestHelper.testAFluidCardNamesFluidsDirectly() {
		val water = FluidResource.of(Fluids.WATER)
		val lava = FluidResource.of(Fluids.LAVA)

		val card = sortingHook(ItemStack(ItemRegistry.ResourceFilterCard)) {
			(it as ResourceConditionState).resourceMatches[0] = lava
		}
		assertTrue(card.accepts(lava)) { "Expected the fluid card to accept the fluid it names" }
		assertTrue(!card.accepts(water)) { "Expected the fluid card to reject a fluid it does not name" }

		val empty = sortingHook(ItemStack(ItemRegistry.ResourceFilterCard)) { }
		assertTrue(!empty.accepts(water)) { "Expected an unconfigured fluid card's blank slots to match nothing" }
		succeed()
	}

	/**
	 * The card compares through `ResourceIdentity`, not `==`.
	 *
	 * `FluidResource` overrides neither `equals` nor `hashCode`, so a card configured with one water
	 * instance and tested against a *different* water instance - which is exactly what happens once
	 * the configured fluid has been through NBT and the tested one has come off a pipe - would never
	 * match under a direct comparison. Two separately constructed instances here reproduce that.
	 */
	@GameTest(template = SMALL)
	fun GameTestHelper.testAFluidCardMatchesAcrossDistinctResourceInstances() {
		val configured = FluidResource.of(Fluids.WATER)
		val tested = FluidResource.of(Fluids.WATER)
		assertTrue(configured !== tested) { "Test needs two distinct instances to be meaningful" }

		val card = sortingHook(ItemStack(ItemRegistry.ResourceFilterCard)) {
			(it as ResourceConditionState).resourceMatches[0] = configured
		}
		assertTrue(card.accepts(tested)) {
			"Expected the card to match an equal-but-distinct FluidResource - a plain == comparison " +
				"would fail here, since FluidResource has no value equality"
		}
		succeed()
	}

	/**
	 * An interface hook's fluid face is a **pass-through junction**: fluid inserted into it is
	 * routed straight into the network as a delivery, never staged in the interface's own buffer.
	 * That directionality is what the subnet-boundary system is built on, and it now holds for
	 * fluids as well as items.
	 */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testAnInterfaceRoutesFluidThroughRatherThanStagingIt() {
		val interfacePos = BlockPos(0, 2, 1)
		val destPos = BlockPos(0, 2, 2)
		setBlock(interfacePos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(destPos, BlockRegistry.FluidTank.defaultBlockState())

		val tile = getBlockEntity(interfacePos) as MultipartBlockEntity
		tile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val state = tile.hooks.getOrPut(Direction.NORTH.name) { InterfaceHookType.createState() } as InterfaceHookState
		placeCreativePressureSource(interfacePos.above())

		val water = FluidResource.of(Fluids.WATER)
		val poured = FluidAmounts.toPlatformAmount(1_000L)

		// Let the segment tick first: a route can only be found once PipeBlockEntity.tick has
		// registered this position into the fluid network, and setBlock runs no placement logic.
		runAfterDelay(5) {
			// Inserted the way a machine or another mod's pipe would - through the exposed face.
			val accepted = state.exposedFluidStorage(tile)!!.insert(water, poured, false)
			assertTrue(accepted == poured) { "Expected the interface to accept the whole insert for routing, got $accepted" }
			assertTrue(fluidHeld(state) == 0L) {
				"Expected nothing to be staged in the interface's own buffer - a pass-through routes, it does not hold"
			}
		}

		succeedWhen {
			val dest = getBlockEntity(destPos) as FluidTankBlockEntity
			assertTrue(dest.storage.getAmount(0) == poured) {
				"Expected the routed fluid to arrive in the tank, got ${dest.storage.getAmount(0)}"
			}
		}
	}

	/** With nowhere on the network willing to take it, a pass-through insert is *rejected* rather than staged - the property that makes an interface a one-way junction. */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testAnInterfaceRejectsFluidItCannotRoute() {
		val interfacePos = BlockPos(0, 2, 1)
		setBlock(interfacePos, BlockRegistry.Multipart.defaultBlockState())

		val tile = getBlockEntity(interfacePos) as MultipartBlockEntity
		tile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val state = tile.hooks.getOrPut(Direction.NORTH.name) { InterfaceHookType.createState() } as InterfaceHookState

		val water = FluidResource.of(Fluids.WATER)
		// Ticked first for the same reason as the routing test above, so this genuinely exercises
		// "the network has nowhere to put it" rather than "the segment isn't in a network yet".
		runAfterDelay(5) {
			val accepted = state.exposedFluidStorage(tile)!!.insert(water, FluidAmounts.toPlatformAmount(1_000L), false)
			assertTrue(accepted == 0L) { "Expected an unroutable insert to be refused outright, got $accepted" }
			assertTrue(fluidHeld(state) == 0L) { "Expected a refused insert to stage nothing" }
			succeed()
		}
	}
}

/**
 * Total fluid sitting in [state]'s own stock row, across every column.
 *
 * By column rather than by slot 0: an interface's stock is one mixed row now
 * ([InterfaceHookState.stock]), so which column a fluid lands in depends on what else is already
 * there - the assertion these tests want is "nothing at all was staged".
 */
private fun fluidHeld(state: InterfaceHookState): Long {
	val fluids = state.stockFor(ResourceKindRegistry.Fluid) ?: return 0L
	return (0 until fluids.size()).sumOf { if (fluids.getResource(it).isBlank) 0L else fluids.getAmount(it) }
}

