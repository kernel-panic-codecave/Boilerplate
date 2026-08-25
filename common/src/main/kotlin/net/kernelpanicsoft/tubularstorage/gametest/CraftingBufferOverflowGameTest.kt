package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.crafting.amountIn
import net.kernelpanicsoft.tubularstorage.pipe.entity.FilterMode
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.RoutingModule
import net.kernelpanicsoft.tubularstorage.pipe.entity.TravelingItem
import net.kernelpanicsoft.tubularstorage.pipe.hook.FilterHookType
import net.kernelpanicsoft.tubularstorage.pipe.hook.SortingHookState
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
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
 * GameTest coverage for a shipment arriving at a Crafting CPU with less free space than the
 * shipment itself - [net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity.tick]'s
 * partial-insertion branch. Everything a Crafting CPU receives travels as a [TravelingItem]
 * whose final hop inserts into the cluster's combined storage, and a job's up-front stock claims
 * routinely exceed what the cluster can hold, so the CPU is the natural place this branch fires.
 * All tests seed state directly (the traveler onto a plain pipe segment, storage straight into
 * the member's own slots) rather than routing through claim logic, so the assertions observe
 * exactly the behavior under test.
 */
@Suppress("unused")
class CraftingBufferOverflowGameTest {
	/**
	 * A 64-diamond shipment into a CPU with 8 diamonds of room (one member's whole 9-slot pool
	 * filled to within 8 of its 576 cap) must end with exactly 8 delivered and exactly 56 still
	 * held by the stalled traveler - conserved, neither duplicated nor lost.
	 */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testPartialInsertionIntoNearlyFullCpuConservesItems() {
		val pipePos = BlockPos(4, 2, 4)
		val cpuPos = BlockPos(4, 2, 5)
		setBlock(pipePos, BlockRegistry.Pipe.defaultBlockState())
		val cpuTile = placeCraftingBuffer(cpuPos)

		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))
		val cpu = cpuTile.craftingBuffer
		cpu.localStorage.insert(diamond, 9 * 64 - 8, false)

		val pipeTile = getBlockEntity(pipePos) as PipeBlockEntity
		pipeTile.travelingItems += TravelingItem(ResourceStack(diamond, 64), Direction.SOUTH, 0f, listOf(absolutePos(cpuPos)))

		succeedWhen {
			assertTrue(pipeTile.travelingItems.size == 1) {
				"Expected the undeliverable remainder to still be held by the stalled traveler, got ${pipeTile.travelingItems.size} travelers"
			}
			val inFlight = pipeTile.travelingItems.filter { it.stack.resource == diamond }.sumOf { it.stack.amount }
			assertTrue(inFlight == 56L) {
				"Expected the traveler to hold exactly the 56 undelivered diamonds, got $inFlight"
			}
			val stored = amountIn(cpu.combinedStorage(cpuTile), diamond)
			assertTrue(stored == 9L * 64) {
				"Expected the CPU's full pool of 576 diamonds (568 pre-filled + 8 accepted), got $stored"
			}
		}
	}

	/**
	 * The same nearly-full CPU, with everything drained out once the remainder has stalled: the
	 * held-back 56 diamonds must complete their delivery on their own, leaving the CPU holding
	 * exactly the remainder and the pipe empty - a shrunk-away remainder must not have been
	 * silently discarded.
	 */
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testStalledRemainderCompletesDeliveryOnceSpaceFrees() {
		val pipePos = BlockPos(4, 2, 4)
		val cpuPos = BlockPos(4, 2, 5)
		setBlock(pipePos, BlockRegistry.Pipe.defaultBlockState())
		val cpuTile = placeCraftingBuffer(cpuPos)

		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))
		val cpu = cpuTile.craftingBuffer
		cpu.localStorage.insert(diamond, 9 * 64 - 8, false)

		val pipeTile = getBlockEntity(pipePos) as PipeBlockEntity
		pipeTile.travelingItems += TravelingItem(ResourceStack(diamond, 64), Direction.SOUTH, 0f, listOf(absolutePos(cpuPos)))

		// Well past the ~20-tick transit plus the first partial acceptance, well before the
		// timeout: the traveler is stalled holding 56 by now. Emptying the pool stands in for the
		// space that appears when a cluster drains or grows.
		runAfterDelay(100) {
			val extracted = cpu.combinedStorage(cpuTile).extract(diamond, 9L * 64, false)
			assertTrue(extracted == 9L * 64) {
				"Expected to be able to empty the CPU's full 576-diamond pool to free space for the remainder, got $extracted"
			}
		}

		succeedWhen {
			assertTrue(pipeTile.travelingItems.isEmpty()) {
				"Expected the stalled remainder to have finished delivering once space freed up, ${pipeTile.travelingItems.size} still in flight"
			}
			val stored = amountIn(cpu.combinedStorage(cpuTile), diamond)
			assertTrue(stored == 56L) {
				"Expected exactly the 56 remaining diamonds of the shipment in the emptied CPU, got $stored"
			}
		}
	}

	/**
	 * Content that lands in a cluster's pool after its job has already completed, drained, and
	 * cleared itself - the late remainder of a delivery that once stalled against a full cluster -
	 * must still be flushed onto the network by the idle leader rather than sit there forever.
	 */
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testJoblessCpuFlushesStrandedStorage() {
		val cpuPos = BlockPos(3, 2, 4)
		val linkPipePos = BlockPos(4, 2, 4)
		val defaultHookPos = BlockPos(5, 2, 4)
		val destPos = BlockPos(6, 2, 4)
		val cpuTile = placeCraftingBuffer(cpuPos)
		setBlock(linkPipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(defaultHookPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		val defaultHook = getBlockEntity(defaultHookPos) as MultipartBlockEntity
		defaultHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val defaultState = defaultHook.hooks.getOrPut(Direction.EAST.name) { FilterHookType.createState() } as SortingHookState
		defaultState.routing = RoutingModule(mode = FilterMode.BLACKLIST, priority = RoutingModule.DEFAULT_ROUTE_PRIORITY)

		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))
		val cpu = cpuTile.craftingBuffer
		cpu.localStorage.insert(diamond, 8, false)

		succeedWhen {
			val dest = getBlockEntity(destPos) as ChestBlockEntity
			val drained = (0 until dest.containerSize).sumOf { if (dest.getItem(it).item == Items.DIAMOND) dest.getItem(it).count else 0 }
			assertTrue(drained == 8) {
				"Expected the stranded CPU storage to have been flushed into the reachable chest, got $drained diamonds"
			}
			assertTrue(amountIn(cpu.combinedStorage(cpuTile), diamond) == 0L) {
				"Expected the CPU's own storage to be empty after the flush"
			}
		}
	}
}
