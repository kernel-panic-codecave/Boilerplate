package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.RoutingModule
import net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionDistribution
import net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.FilterHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState
import net.kernelpanicsoft.boilerplate.pipe.network.ItemNetworkType
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.ModConditionState
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity

/**
 * Coverage for the extraction hook's own configuration - its filter, how much one pull moves, and
 * how it chooses between destinations.
 *
 * The pull itself is already covered by [PipeExtractionGameTest]; what these pin is that each
 * setting actually reaches the extraction loop, and - as much as anything here - that the defaults
 * leave an unconfigured hook behaving exactly as it did before any of this was settable.
 */
@Suppress("unused")
class ExtractionHookConfigGameTest {

	/**
	 * A chest, an extractor pulling from it, and a chest to deliver into, with [configure] run on the
	 * hook before the world ticks.
	 *
	 * @return the destination chest's position.
	 */
	private fun GameTestHelper.layOutExtractor(
		contents: List<ItemStack>,
		configure: ExtractionHookState.() -> Unit,
	): BlockPos {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val destPos = BlockPos(0, 2, 2)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		val source = getBlockEntity(sourcePos) as ChestBlockEntity
		for ((slot, stack) in contents.withIndex()) source.setItem(slot, stack)

		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val hook = extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() } as ExtractionHookState
		hook.configure()
		placeCreativePressureSource(extractorPos.above())
		return destPos
	}

	/** A whitelist card matching everything in the `minecraft` namespace. */
	private fun vanillaCard(): ItemStack {
		val card = ItemStack(ItemRegistry.ModFilterCard)
		FilterCardState(card).apply {
			(currentState() as ModConditionState).modId = "minecraft"
			touchCurrentState()
		}
		return card
	}

	/**
	 * An unconfigured hook pulls whatever is in front of it.
	 *
	 * The regression that matters most here: every extractor placed before the filter existed has an
	 * empty card slot, and reading that as an empty whitelist - which is exactly what a *sorting*
	 * hook does - would have stopped all of them dead. See [ExtractionHookState.accepts].
	 */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testAnEmptyFilterPullsEverything() {
		val destPos = layOutExtractor(listOf(ItemStack(Items.DIAMOND, 8))) {}

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.DIAMOND)) {
				"Expected an unconfigured extractor to pull anyway, got ${dest.getItem(0)}"
			}
		}
	}

	/** A blacklist card keeps the hook off what it names, and lets everything else past. */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testABlacklistedResourceIsNeverPulled() {
		val destPos = layOutExtractor(listOf(ItemStack(Items.DIAMOND, 8))) {
			filterMode = FilterMode.BLACKLIST
			filter.insert(ItemResource.of(vanillaCard()), 1, false)
		}

		runAfterDelay(100L) {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).isEmpty) {
				"Expected a blacklisted resource to stay in the source chest, got ${dest.getItem(0)} at the destination"
			}
			succeed()
		}
	}

	/** The whitelisted side of the same card - what it names is exactly what moves. */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testAWhitelistedResourceIsPulled() {
		val destPos = layOutExtractor(listOf(ItemStack(Items.DIAMOND, 8))) {
			filterMode = FilterMode.WHITELIST
			filter.insert(ItemResource.of(vanillaCard()), 1, false)
		}

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.DIAMOND)) {
				"Expected a whitelisted resource to be pulled, got ${dest.getItem(0)}"
			}
		}
	}

	/**
	 * The amount is what one pull moves, not what the source holds - a hook set to three leaves the
	 * rest behind for the next pull.
	 */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testTheConfiguredAmountIsWhatOnePullMoves() {
		val destPos = layOutExtractor(listOf(ItemStack(Items.DIAMOND, 64))) {
			amountAuthored = 3L
			// Slow enough that the first delivery lands well before a second pull could confuse the
			// count being asserted.
			intervalTicks = 100
		}

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.DIAMOND) && dest.getItem(0).count == 3) {
				"Expected exactly one pull of 3 to have arrived, got ${dest.getItem(0)}"
			}
		}
	}

	/**
	 * An interval long enough to outlast the test means nothing is ever pulled - the speed setting
	 * genuinely gates the loop rather than being read and ignored.
	 */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testALongIntervalHoldsTheHookOff() {
		val destPos = layOutExtractor(listOf(ItemStack(Items.DIAMOND, 8))) {
			intervalTicks = ExtractionHookState.MAX_INTERVAL_TICKS
		}

		runAfterDelay(120L) {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).isEmpty) {
				"Expected nothing to have been pulled yet at a ${ExtractionHookState.MAX_INTERVAL_TICKS}-tick interval, got ${dest.getItem(0)}"
			}
			succeed()
		}
	}

	/**
	 * Nearest-first keeps no cursor, so nothing is ever excluded from the route search.
	 *
	 * Asserted on the state rather than on where items land: with one destination both modes deliver
	 * identically, and standing up two genuinely peer destinations tests the router's ranking rather
	 * than this setting. What distinguishes the modes *here* is whether the cursor fills.
	 */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testNearestFirstKeepsNoRoundRobinCursor() {
		val destPos = layOutExtractor(listOf(ItemStack(Items.DIAMOND, 8))) {
			distribution = ExtractionDistribution.NEAREST_FIRST
		}
		val extractor = getBlockEntity(BlockPos(0, 2, 1)) as MultipartBlockEntity

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.DIAMOND)) {
				"Expected nearest-first to deliver, got ${dest.getItem(0)}"
			}
			val hook = extractor.hooks[Direction.NORTH.name] as ExtractionHookState
			assertTrue(hook.servedThisCycle.isEmpty()) {
				"Expected nearest-first to leave the round-robin cursor untouched, got ${hook.servedThisCycle}"
			}
		}
	}

	/**
	 * Round robin serves in priority order - the cursor narrows the field, it does not flatten it.
	 *
	 * Two destinations, one ranked above the other. Asked with nothing excluded the router picks the
	 * higher-ranked one, which is what a round's first pull gets; asked again with that one excluded -
	 * exactly what the cursor does on the next pull - it picks the other. Priority deciding the
	 * *order* of a round is the whole distinction between this and a mode that ignores ranking.
	 *
	 * Asserted on the routing decision rather than on where items end up: the decision is the thing
	 * this setting actually changes, and it is answerable in one tick instead of depending on several
	 * segments' worth of travel time landing inside a test window.
	 */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testRoundRobinAdvancesToTheNextBestDestination() {
		val extractorPos = BlockPos(0, 2, 1)
		val junctionPos = BlockPos(0, 2, 2)
		val lowPipePos = BlockPos(0, 2, 3)
		val lowDestPos = BlockPos(0, 2, 4)
		val highPipePos = BlockPos(1, 2, 2)
		val highDestPos = BlockPos(2, 2, 2)

		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(junctionPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(lowPipePos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(lowDestPos, Blocks.CHEST.defaultBlockState())
		setBlock(highPipePos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(highDestPos, Blocks.CHEST.defaultBlockState())

		for (pipe in listOf(extractorPos, junctionPos, lowPipePos, highPipePos)) {
			(getBlockEntity(pipe) as MultipartBlockEntity).pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		}

		// Both accept diamonds; only their priorities differ, so nothing but the ranking can decide
		// which one a pull is offered.
		fun gate(pipePos: BlockPos, face: Direction, priority: Int) {
			val pipe = getBlockEntity(pipePos) as MultipartBlockEntity
			val gateState = pipe.hooks.getOrPut(face.name) { FilterHookType.createState() } as SortingHookState
			gateState.routing = RoutingModule(mode = FilterMode.WHITELIST, priority = priority)
			pipe.filterFor(face).insert(ItemResource.of(vanillaCard()), 1, false)
		}
		gate(highPipePos, Direction.EAST, priority = 9)
		gate(lowPipePos, Direction.SOUTH, priority = 1)

		// One tick for the segments to register into a network before anything is routed across it.
		runAfterDelay(2L) {
			val diamond = ItemResource.of(ItemStack(Items.DIAMOND))
			val from = absolutePos(extractorPos)

			val first = ItemNetworkType.route(level, from, ResourceStack(diamond, 1))
			assertTrue(first?.lastOrNull() == absolutePos(highDestPos)) {
				"Expected the first pull of a round to go to the higher-priority destination, got $first"
			}

			// What the round-robin cursor asks for on the next pull, having served the first.
			val second = ItemNetworkType.route(level, from, ResourceStack(diamond, 1), exclude = setOf(absolutePos(highDestPos)))
			assertTrue(second?.lastOrNull() == absolutePos(lowDestPos)) {
				"Expected the round to move on to the next-best destination once the first was served, got $second"
			}
			succeed()
		}
	}

	/** Round robin - the default - records what it served so the next pull moves on. */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testRoundRobinRecordsWhatItServed() {
		val destPos = layOutExtractor(listOf(ItemStack(Items.DIAMOND, 8))) {
			distribution = ExtractionDistribution.ROUND_ROBIN
		}
		val extractor = getBlockEntity(BlockPos(0, 2, 1)) as MultipartBlockEntity

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			assertTrue(dest.getItem(0).`is`(Items.DIAMOND)) {
				"Expected round robin to deliver, got ${dest.getItem(0)}"
			}
			val hook = extractor.hooks[Direction.NORTH.name] as ExtractionHookState
			assertTrue(hook.servedThisCycle.contains(absolutePos(destPos))) {
				"Expected the served destination to be recorded for the next pull, got ${hook.servedThisCycle}"
			}
		}
	}
}
