package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.config.BoilerplateConfig
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.ExtractionHookType
import net.kernelpanicsoft.boilerplate.pipe.network.InboundCensus
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
 * GameTest coverage for [InboundCensus] - an extractor sizing its pull against what is *already* on
 * its way to the destination, not only against the room the destination reports.
 *
 * The failure this guards is not subtle: a destination's storage answers for itself and knows
 * nothing of the pipes pointing at it, so an extractor that only asks it will keep pulling a full
 * batch every interval for the whole time the first one is still in transit. Everything after the
 * first stalls at the last segment, out of its source and unreachable to anything else.
 */
@Suppress("unused")
class ExtractionInFlightGameTest {

	/**
	 * A destination that cannot take a single item is not targeted at all, so nothing sets off for
	 * it and nothing queues at its door.
	 *
	 * The router's own admission test ([net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter])
	 * is what decides this, not the queue: a full chest is not a destination, and treating it as one
	 * would have every full chest on a network go on attracting deliveries that then wait there
	 * instead of going to a chest with room. The queue is primed from the first item of room a
	 * destination frees, which for anything actually consuming is immediately.
	 */
	@GameTest(template = SMALL, timeoutTicks = 120)
	fun GameTestHelper.testAFullDestinationIsNotTargetedAtAll() {
		val sourcePos = BlockPos(0, 2, 0)
		val destPos = BlockPos(0, 2, 4)
		buildRun(sourcePos, destPos)

		val dest = getBlockEntity(destPos) as ChestBlockEntity
		for (slot in 0 until dest.containerSize) dest.setItem(slot, ItemStack(Items.DIAMOND, 64))

		runAfterDelay(100) {
			val queued = queuedInPipes()
			assertTrue(queued == 0L) { "Expected nothing to set off for a full destination, got $queued in the pipes" }
			val remaining = sourceCount(sourcePos)
			assertTrue(remaining == SOURCE_TOTAL) { "Expected the source untouched, got $remaining" }
			succeed()
		}
	}

	/**
	 * A destination with room for one batch takes that batch **and** has more waiting behind it -
	 * but never more than the configured queue.
	 *
	 * Both halves are the fix. Committing only the room that exists at the moment of the pull is
	 * what starves a destination that is consuming: the delivery arrives a whole trip later, by
	 * which time the room it was sized against has long since been used and refilled. Committing
	 * without a ceiling is the original fault - three segments at the default speed put the first
	 * delivery a good way out, and a hook pulling every ten ticks will fill the whole run with
	 * batches for one batch of room.
	 */
	@GameTest(template = SMALL, timeoutTicks = 260)
	fun GameTestHelper.testTheQueueIsPrimedBeyondTheRoomAndBounded() {
		val sourcePos = BlockPos(0, 2, 0)
		val destPos = BlockPos(0, 2, 4)
		buildRun(sourcePos, destPos)

		// Every slot but the last one full: room for exactly one batch and not a diamond more.
		val dest = getBlockEntity(destPos) as ChestBlockEntity
		for (slot in 0 until dest.containerSize - 1) dest.setItem(slot, ItemStack(Items.DIAMOND, 64))

		runAfterDelay(250) {
			val destination = getBlockEntity(destPos) as ChestBlockEntity
			val landed = destination.getItem(destination.containerSize - 1)
			assertTrue(landed.`is`(Items.DIAMOND) && landed.count == 64) {
				"Expected the one batch of room to have been filled, got $landed"
			}

			val queued = queuedInPipes()
			assertTrue(queued > 0L) { "Expected the queue to have been primed past the room, got nothing waiting" }
			assertTrue(queued <= queueHeadroom()) {
				"Expected no more than the configured queue (${queueHeadroom()}) waiting in the pipes, got $queued"
			}
			val remaining = sourceCount(sourcePos)
			assertTrue(remaining == SOURCE_TOTAL - 64 - queued) {
				"Expected the source to account for exactly what left it, got $remaining"
			}
			succeed()
		}
	}

	/**
	 * A hook set to queue nothing sends only what fits, however much the pack-wide default allows.
	 *
	 * The per-hook setting is the whole point of the figure being on the hook rather than only in
	 * the config: a short hop into a chest wants none of the slack a long haul to a machine needs,
	 * and both live on the same network.
	 */
	@GameTest(template = SMALL, timeoutTicks = 260)
	fun GameTestHelper.testAHookMaySendNoQueueAtAll() {
		val sourcePos = BlockPos(0, 2, 0)
		val destPos = BlockPos(0, 2, 4)
		buildRun(sourcePos, destPos, queueWholes = 0)

		// Every slot but the last one full: room for exactly one batch and not a diamond more.
		val dest = getBlockEntity(destPos) as ChestBlockEntity
		for (slot in 0 until dest.containerSize - 1) dest.setItem(slot, ItemStack(Items.DIAMOND, 64))

		runAfterDelay(250) {
			val queued = queuedInPipes()
			assertTrue(queued == 0L) { "Expected a hook with no queue to leave the pipes empty, got $queued" }
			val remaining = sourceCount(sourcePos)
			assertTrue(remaining == SOURCE_TOTAL - 64) {
				"Expected exactly the one batch of room to have left the source, got ${SOURCE_TOTAL - remaining} gone"
			}
			succeed()
		}
	}

	/**
	 * The census reports a delivery the moment it enters the network, before any pipe has ticked.
	 *
	 * A hook pulling twice inside one tick - or two hooks aimed at the same destination - would
	 * otherwise both read a figure that predates either of their own pulls and commit the same room
	 * over again.
	 */
	@GameTest(template = SMALL, timeoutTicks = 60)
	fun GameTestHelper.testACommittedDeliveryIsVisibleOnTheSameTick() {
		val pipePos = BlockPos(0, 2, 1)
		val destPos = BlockPos(0, 2, 2)
		plainPipe(pipePos)
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		runAfterDelay(5) {
			val level = level
			val destination = absolutePos(destPos)
			val resource: ResourceComponent = ItemResource.of(Items.DIAMOND)

			// Asking is what starts the destination being counted, so nothing before it registers -
			// see InboundCensus's own KDoc on why counting is opt-in.
			InboundCensus.headedFor(level, destination, resource)
			val pipe = getBlockEntity(pipePos) as PipeBlockEntity
			pipe.acceptEntry(
				earth.terrarium.common_storage_lib.resources.ResourceStack(resource, 32L),
				Direction.NORTH,
				listOf(destination),
			)

			val headed = InboundCensus.headedFor(level, destination, resource)
			assertTrue(headed == 32L) { "Expected the just-committed 32 to be visible immediately, got $headed" }
			succeed()
		}
	}

	/** TEMP PROBE: an empty destination must still fill completely. */
	@GameTest(template = SMALL, timeoutTicks = 600)
	fun GameTestHelper.testProbeEmptyDestinationFills() {
		val sourcePos = BlockPos(0, 2, 0)
		val extractorPos = BlockPos(0, 2, 1)
		val destPos = BlockPos(0, 2, 4)
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		setBlock(extractorPos, BlockRegistry.Multipart.defaultBlockState())
		for (z in 2..3) plainPipe(BlockPos(0, 2, z))
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		val source = getBlockEntity(sourcePos) as ChestBlockEntity
		for (slot in 0 until 6) source.setItem(slot, ItemStack(Items.DIAMOND, 64))

		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		extractor.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() }
		placeCreativePressureSource(extractorPos.above())

		runAfterDelay(560) {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			val arrived = (0 until dest.containerSize).sumOf { dest.getItem(it).count }
			val left = (0 until 6).sumOf { (getBlockEntity(sourcePos) as ChestBlockEntity).getItem(it).count }
			val stalled = (1..3).sumOf { z ->
				(getBlockEntity(BlockPos(0, 2, z)) as? PipeBlockEntity)?.travelingItems?.sumOf { it.stack.amount } ?: 0L
			}
			assertTrue(arrived == 384) { "PROBE arrived=$arrived left=$left stalled=$stalled" }
			succeed()
		}
	}

	private fun GameTestHelper.plainPipe(pos: BlockPos) {
		setBlock(pos, BlockRegistry.Multipart.defaultBlockState())
		(getBlockEntity(pos) as MultipartBlockEntity).pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
	}

	/**
	 * A chest, an extracting segment, two plain ones and a chest - three hops from source to
	 * destination, with the hook's own queue left at whatever the config seeds it with unless
	 * [queueWholes] names one.
	 */
	private fun GameTestHelper.buildRun(sourcePos: BlockPos, destPos: BlockPos, queueWholes: Int? = null) {
		val extractorPos = sourcePos.south()
		setBlock(sourcePos, Blocks.CHEST.defaultBlockState())
		plainPipe(extractorPos)
		for (z in 2..3) plainPipe(BlockPos(0, 2, z))
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		// Always more to over-commit with than either the destination or the queue can take.
		val source = getBlockEntity(sourcePos) as ChestBlockEntity
		for (slot in 0 until SOURCE_STACKS) source.setItem(slot, ItemStack(Items.DIAMOND, 64))

		val extractor = getBlockEntity(extractorPos) as MultipartBlockEntity
		val hook = extractor.hooks.getOrPut(Direction.NORTH.name) { ExtractionHookType.createState() } as ExtractionHookState
		if (queueWholes != null) hook.queueWholes = queueWholes
		placeCreativePressureSource(extractorPos.above())
	}

	private fun GameTestHelper.sourceCount(sourcePos: BlockPos): Long =
		(0 until SOURCE_STACKS).sumOf { (getBlockEntity(sourcePos) as ChestBlockEntity).getItem(it).count.toLong() }

	private fun GameTestHelper.queuedInPipes(): Long = (1..3).sumOf { z ->
		(getBlockEntity(BlockPos(0, 2, z)) as? PipeBlockEntity)?.travelingItems?.sumOf { it.stack.amount } ?: 0L
	}

	/**
	 * The slack the run is allowed, in diamonds.
	 *
	 * The config names it in whole units of the resource's own kind, and a whole of an item is that
	 * item's own stack size - 64 for the diamonds these fixtures use, but 16 for an ender pearl and
	 * one for a shulker box, so the `* 64` here is about the fixture rather than about items.
	 */
	private fun queueHeadroom(): Long = BoilerplateConfig.Gameplay.Pipes.destinationQueueWholes * 64L

	private companion object {
		const val SOURCE_STACKS = 8
		const val SOURCE_TOTAL = SOURCE_STACKS * 64L
	}
}
