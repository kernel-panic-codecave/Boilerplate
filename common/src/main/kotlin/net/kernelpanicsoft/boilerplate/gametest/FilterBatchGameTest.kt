package net.kernelpanicsoft.boilerplate.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.boilerplate.resource.roomFor
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.kernelpanicsoft.boilerplate.pipe.hook.FilterHookState
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionHookType
import net.kernelpanicsoft.boilerplate.crafting.Pattern
import net.kernelpanicsoft.boilerplate.crafting.PatternItemData
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.kernelpanicsoft.boilerplate.pipe.hook.FilterHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookType
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.resource.resourceCell
import net.kernelpanicsoft.boilerplate.pipe.hook.batchAtRouteEnd
import net.kernelpanicsoft.boilerplate.pipe.hook.batchForRoute
import net.kernelpanicsoft.boilerplate.pipe.hook.batchedForRoute
import net.kernelpanicsoft.boilerplate.resource.FluidKind
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import net.minecraft.world.level.material.Fluids
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
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
 * A filter hook with a batch size lets a transfer across its face carry only whole multiples.
 *
 * The problem: a machine whose recipe consumes a fixed number at a time - three raw ore to a slurry
 * - is jammed rather than helped by a partial delivery. It holds the remainder and stalls until the
 * rest happens to arrive, which for a trickle of sorted output can be never.
 *
 * The hook holds nothing itself. Rounding the transfer down leaves the surplus in whatever it was
 * being pulled from - a barrel ahead of an extraction hook, the warehouse - which is somewhere the
 * player can already see and reach, rather than a second hiding place inside a pipe.
 */
@Suppress("unused")
class FilterBatchGameTest {

	/** A pipe carrying a filter hook on its [Direction.NORTH] face, batching in [batch]. */
	private fun GameTestHelper.layOutBatchingHook(batch: Long): Pair<FilterHookState, BlockPos> {
		val pipePos = BlockPos(1, 2, 1)
		setBlock(pipePos, BlockRegistry.Multipart.defaultBlockState())
		val tile = getBlockEntity(pipePos) as MultipartBlockEntity
		tile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val state = tile.hooks.getOrPut(Direction.NORTH.name) { FilterHookType.createState() } as FilterHookState
		state.batchSize = batch
		return state to pipePos
	}

	/** An unset hook imposes nothing, so an unbatched line behaves exactly as it always has. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testAnUnsetHookImposesNoMultiple() {
		val (state, pipePos) = layOutBatchingHook(batch = FilterHookState.NOT_BATCHED)
		val destination = absolutePos(pipePos).relative(Direction.NORTH)

		assertTrue(state.effectiveBatchSize() == FilterHookState.NOT_BATCHED) { "A fresh hook should impose no multiple" }
		assertTrue(batchAtRouteEnd(level, absolutePos(pipePos), listOf(destination)) == FilterHookState.NOT_BATCHED) {
			"An unset hook must not constrain a route across its face"
		}
		succeed()
	}

	/** The multiple is read from the hook on the face the delivery actually crosses. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testTheMultipleIsReadFromTheCrossedFace() {
		val (_, pipePos) = layOutBatchingHook(batch = 3)
		val destination = absolutePos(pipePos).relative(Direction.NORTH)

		assertTrue(batchAtRouteEnd(level, absolutePos(pipePos), listOf(destination)) == 3L) {
			"Expected the hook on the crossed face to impose its multiple of 3"
		}
		// A different face of the same pipe carries no hook, so nothing is imposed there.
		val elsewhere = absolutePos(pipePos).relative(Direction.SOUTH)
		assertTrue(batchAtRouteEnd(level, absolutePos(pipePos), listOf(elsewhere)) == FilterHookState.NOT_BATCHED) {
			"A face with no hook must impose nothing, even on a pipe that batches elsewhere"
		}
		succeed()
	}

	/**
	 * A batch is authored in the unit a player types, and applied in the unit the resource being
	 * moved is actually counted in.
	 *
	 * One face gates every kind that can cross it, so the number cannot be a platform count: a fluid
	 * is counted in droplets on Fabric and millibuckets on NeoForge, and a saved `1000` would have
	 * meant a bucket on one loader and a eightieth of one on the other. Authored, it is a bucket on
	 * both - and still a thousand items when an item crosses the same face.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testABatchMeansTheSameVolumeOnEitherLoader() {
		// One whole of a fluid, in the unit a player types it in.
		val aBucket = FluidKind.defaultAuthored
		val (_, pipePos) = layOutBatchingHook(batch = aBucket)
		val destination = absolutePos(pipePos).relative(Direction.NORTH)
		val from = absolutePos(pipePos)
		val water = FluidResource.of(Fluids.WATER)
		val bucket = FluidKind.toPlatform(aBucket)

		assertTrue(batchForRoute(level, from, listOf(destination), water) == bucket) {
			"Expected a batch of $aBucket to gate one bucket ($bucket in this " +
				"platform's own count), got ${batchForRoute(level, from, listOf(destination), water)}"
		}
		// Two buckets pass whole; one and a half is rounded back down to one.
		assertTrue(batchedForRoute(level, from, listOf(destination), water, bucket * 2) == bucket * 2) {
			"Expected two whole buckets to cross a bucket-batched face"
		}
		assertTrue(batchedForRoute(level, from, listOf(destination), water, bucket + bucket / 2) == bucket) {
			"Expected a bucket and a half to be rounded down to one whole bucket"
		}
		// The same face, the same number, an item crossing it: a thousand items.
		val diamond = ItemResource.of(Items.DIAMOND)
		assertTrue(batchForRoute(level, from, listOf(destination), diamond) == aBucket) {
			"Expected an item to read the same authored number as a plain count of itself"
		}
		succeed()
	}

	/**
	 * The configurable ceiling has to reach one whole of the largest kind that can cross a face.
	 *
	 * It was a flat, item-shaped `64`, which put every measured kind out of reach - a fluid delivery
	 * is a whole bucket, so the entire settable range expressed less than a sixteenth of one and a
	 * fluid line could not be batched at all.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testTheBatchCeilingReachesAWholeBucket() {
		val aBucket = FluidKind.defaultAuthored
		assertTrue(FilterHookState.maxBatchSize >= aBucket) {
			"Expected the batch ceiling to reach at least one bucket, got ${FilterHookState.maxBatchSize}"
		}
		assertTrue(FilterHookState.maxBatchSize >= 64L) {
			"Expected the batch ceiling to still reach a stack, got ${FilterHookState.maxBatchSize}"
		}
		succeed()
	}

	/** A negative size would mean "accept nothing, ever", so it floors to no restriction. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testANegativeSizeIsTreatedAsUnset() {
		val (state, _) = layOutBatchingHook(batch = -5)
		assertTrue(state.effectiveBatchSize() == FilterHookState.NOT_BATCHED) {
			"Expected a negative batch size to floor to no restriction, got ${state.effectiveBatchSize()}"
		}
		succeed()
	}

	/**
	 * Rounding down is what leaves the remainder at the source.
	 *
	 * The arithmetic the extraction path applies: five available against a multiple of three moves
	 * three and leaves two behind, and two available moves nothing at all rather than jamming the
	 * destination with a partial batch.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testATransferIsRoundedDownToTheMultiple() {
		fun batched(available: Long, batch: Long) = (available / batch) * batch

		assertTrue(batched(5, 3) == 3L) { "Five against a multiple of three should move three" }
		assertTrue(batched(2, 3) == 0L) { "Two against a multiple of three should move nothing" }
		assertTrue(batched(6, 3) == 6L) { "An exact multiple should move in full" }
		assertTrue(batched(64, 3) == 63L) { "A full batch pull should round down to 63" }
		succeed()
	}

	/**
	 * End to end: an extractor pulling through a batching face delivers a whole multiple and leaves
	 * the remainder at the source.
	 *
	 * The arrangement this is all for - `source -> extractor -> batching filter -> machine` - with
	 * real blocks and a real tick, rather than the arithmetic in isolation. Eight diamonds against a
	 * multiple of three should move six and leave two behind.
	 */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testAnExtractionThroughABatchingFaceMovesWholeMultiples() {
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

		// The batching face is the one the delivery crosses: south, toward the destination.
		val filter = extractor.hooks.getOrPut(Direction.SOUTH.name) { FilterHookType.createState() } as FilterHookState
		filter.routing = filter.routing.copy(mode = FilterMode.BLACKLIST)
		filter.batchSize = 3
		placeCreativePressureSource(extractorPos.above())

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			val source = getBlockEntity(sourcePos) as ChestBlockEntity
			assertTrue(dest.getItem(0).count == 6) {
				"Expected 6 delivered as two whole batches of 3, got ${dest.getItem(0).count}"
			}
			assertTrue(source.getItem(0).count == 2) {
				"Expected the 2 that cannot make a batch to stay at the source, got ${source.getItem(0).count}"
			}
		}
	}

	/**
	 * An in-flight batch that no longer fits whole waits in the pipe rather than part-filling the
	 * destination.
	 *
	 * The case batching at extraction time cannot cover, and the one that actually showed up in
	 * game: the delivery was pulled when the machine had room for a whole batch, and by the time it
	 * arrived - the machine having been fed by other deliveries meanwhile - only part of it fits.
	 * Inserting that part is precisely the jam batching exists to prevent, so the arrival is the
	 * last place the multiple has to be enforced.
	 *
	 * Staged directly, by handing the pipe a delivery, because the race is not reproducible by
	 * timing: the point is a destination whose room shrank *after* the pull.
	 */
	@GameTest(template = SMALL, timeoutTicks = 100)
	fun GameTestHelper.testAnArrivingBatchThatNoLongerFitsWholeWaitsInThePipe() {
		val pipePos = BlockPos(0, 2, 1)
		val destPos = BlockPos(0, 2, 2)
		setBlock(pipePos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		// Room for exactly two more diamonds: one part-filled diamond slot, everything else full of
		// something diamonds cannot join.
		val dest = getBlockEntity(destPos) as ChestBlockEntity
		dest.setItem(0, ItemStack(Items.DIAMOND, 62))
		for (slot in 1 until dest.containerSize) dest.setItem(slot, ItemStack(Items.DIRT, 64))

		val pipe = getBlockEntity(pipePos) as MultipartBlockEntity
		pipe.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val filter = pipe.hooks.getOrPut(Direction.SOUTH.name) { FilterHookType.createState() } as FilterHookState
		filter.routing = filter.routing.copy(mode = FilterMode.BLACKLIST)
		filter.batchSize = 3

		pipe.travelingItems += TravelingItem(
			stack = ResourceStack(ItemResource.of(Items.DIAMOND), 3L),
			fromDirection = Direction.NORTH,
			path = listOf(absolutePos(destPos)),
		)

		runAfterDelay(40) {
			val slot = (getBlockEntity(destPos) as ChestBlockEntity).getItem(0)
			assertTrue(slot.count == 62) {
				"Expected the batch to wait rather than top the slot up past a whole multiple, got ${slot.count}"
			}
			assertTrue(pipe.travelingItems.size == 1) {
				"Expected the delivery to still be held in the pipe, got ${pipe.travelingItems.size} in transit"
			}
			succeed()
		}
	}

	/** ...and it lands, whole, as soon as the destination has room for the whole multiple again. */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testAWaitingBatchLandsOnceTheWholeMultipleFits() {
		val pipePos = BlockPos(0, 2, 1)
		val destPos = BlockPos(0, 2, 2)
		setBlock(pipePos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())
		placeCreativePressureSource(pipePos.above())

		val dest = getBlockEntity(destPos) as ChestBlockEntity
		dest.setItem(0, ItemStack(Items.DIAMOND, 62))
		for (slot in 1 until dest.containerSize) dest.setItem(slot, ItemStack(Items.DIRT, 64))

		val pipe = getBlockEntity(pipePos) as MultipartBlockEntity
		pipe.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val filter = pipe.hooks.getOrPut(Direction.SOUTH.name) { FilterHookType.createState() } as FilterHookState
		filter.routing = filter.routing.copy(mode = FilterMode.BLACKLIST)
		filter.batchSize = 3

		pipe.travelingItems += TravelingItem(
			stack = ResourceStack(ItemResource.of(Items.DIAMOND), 3L),
			fromDirection = Direction.NORTH,
			path = listOf(absolutePos(destPos)),
		)

		// The machine consumes: room for a whole batch of three opens up.
		runAfterDelay(40) {
			(getBlockEntity(destPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 60))
		}
		runAfterDelay(80) {
			val slot = (getBlockEntity(destPos) as ChestBlockEntity).getItem(0)
			assertTrue(slot.count == 63) {
				"Expected the held batch to land whole once it fits, got ${slot.count}"
			}
			succeed()
		}
	}

	/**
	 * A real insert into a part-filled container takes only what fits - and a simulated one is not
	 * required to agree, which is why the batching gate measures rather than predicts.
	 *
	 * The disagreement is upstream and **loader-specific**: on NeoForge, Common Storage Lib's
	 * whole-storage insert walks the slot list twice - occupied slots, then every slot - so a
	 * simulation, having moved nothing, is offered the same free space by both passes and a chest
	 * holding 62 of a 64 stack answers "3" to a request for 3 while really taking 2. Fabric's own
	 * wrapper does not do this and answers 2 on both. That is exactly why nothing may be built on
	 * the simulated number: it is right on one loader and wrong on the other.
	 *
	 * Working around it by walking slots directly is *not* sound either: that assumes a storage's
	 * slots are a faithful partition whose admission matches the storage's own, which several of
	 * this mod's do not - see the pattern-buffer case below. So
	 * [roomFor] asks the storage, and the arrival gate in
	 * [net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity] needs no prediction at all.
	 */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testARealInsertTakesOnlyWhatFitsInAPartFilledContainer() {
		val destPos = BlockPos(0, 2, 2)
		setBlock(destPos, Blocks.CHEST.defaultBlockState())
		val dest = getBlockEntity(destPos) as ChestBlockEntity
		dest.setItem(0, ItemStack(Items.DIAMOND, 62))
		for (slot in 1 until dest.containerSize) dest.setItem(slot, ItemStack(Items.DIRT, 64))

		val storage = ItemApi.BLOCK.find(level, absolutePos(destPos), Direction.NORTH)!!
		val diamond = ItemResource.of(Items.DIAMOND)
		val simulated = storage.insert(diamond, 3L, true)
		val real = storage.insert(diamond, 3L, false)
		assertTrue(real == 2L) { "Expected a real insert to take only the 2 that fit, got $real" }
		// Never `==`: NeoForge over-reports here and Fabric does not. The invariant that holds on
		// both - and the only one anything may rely on - is that a simulate is an upper bound.
		assertTrue(simulated >= real) {
			"Expected a simulated insert to be an upper bound on a real one, got $simulated against $real"
		}
		succeed()
	}

	/**
	 * ...and the reported room has to agree with a real insert for *this mod's own* storages too,
	 * not only for a vanilla container.
	 *
	 * The regression this pins: an earlier fix for the upstream over-report above walked a storage's
	 * slots directly instead of asking it. That assumes `get(index)` is a faithful partition whose
	 * slots admit what the storage admits - and [PatternBufferIO] is a deliberate counter-example.
	 * Its own `insert` meters by run boundary, caps at
	 * configured buffered runs, and refuses a resource no
	 * pattern here requires; its slots do none of that. A walk therefore reported room where the
	 * storage would in fact take nothing, and a delivery sized by that number arrives, is refused,
	 * and stalls in the pipe - retried forever while the extractor keeps sending more.
	 */
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testReportedRoomAgreesWithARealInsertForAPatternBuffer() {
		val hookPos = BlockPos(0, 2, 2)
		setBlock(hookPos, BlockRegistry.Multipart.defaultBlockState())
		val tile = getBlockEntity(hookPos) as MultipartBlockEntity
		tile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val state = tile.hooks.getOrPut(Direction.NORTH.name) { PatternProviderHookType.createState() } as PatternProviderHookState
		state.patterns[0].set(
			ItemStack(ItemRegistry.Pattern).also {
				PatternItemData(it).pattern = Pattern(
					inputs = listOf(ItemStack(Items.OAK_PLANKS).resourceCell, ItemStack(Items.OAK_PLANKS).resourceCell),
					outputs = listOf(ItemStack(Items.STICK, 4).resourceCell),
					kind = PatternKind.PROCESSING,
				)
			},
		)

		val buffer = state.exposedItemStorage(tile)
		val planks = ItemResource.of(Items.OAK_PLANKS)
		val reported = buffer.roomFor(planks, 2L)
		assertTrue(reported > 0L) {
			"Expected a pattern buffer wanting planks to report room for them, got $reported - " +
				"a delivery gated on this would stall in the pipe and the craft would never progress"
		}
		val real = buffer.insert(planks, 2L, false)
		assertTrue(real == reported) {
			"Expected the reported room ($reported) to be exactly what a real insert takes ($real)"
		}

		// The direction that actually stalls a pipe: a resource this hook holds no pattern for.
		// Its slots would happily take one; the storage itself will not, and that is the answer a
		// caller sizing a delivery has to get.
		val unwanted = ItemResource.of(Items.DIAMOND)
		assertTrue(buffer.roomFor(unwanted, 8L) == 0L) {
			"Expected a pattern buffer to report no room for a resource no pattern here needs, got " +
				"${buffer.roomFor(unwanted, 8L)} - a delivery sized by that arrives, is refused, and stalls in the pipe"
		}
		succeed()
	}
}
