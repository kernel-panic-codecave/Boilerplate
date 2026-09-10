package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.resource.roomFor
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookType
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import earth.terrarium.common_storage_lib.item.ItemApi
import net.minecraft.server.level.ServerLevel

/**
 * An interface's pass-through surface only accepts what the far side can actually take.
 *
 * A push into an interface is not staged - it is routed onward immediately (see
 * [net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookState]). That made it easy to report
 * acceptance of the *whole* insert as soon as any route existed at all, which is a promise the
 * interface is in no position to make: the far destination might have room for one item. Whatever it
 * over-accepted then travelled to that destination and stalled there, retrying forever, because a
 * traveling item that cannot be deposited is held rather than dropped.
 *
 * Any pusher hits this - a filter hook across a boundary is just the usual way to arrange it - since
 * every pusher sizes its push by what the destination says it will accept.
 */
@Suppress("unused")
class InterfaceCapacityGameTest {

	/**
	 * An interface whose onward route ends at a nearly-full chest. Returns the interface tile, its
	 * hook, and **the chest's own answer** to "how many diamonds will you take" - measured rather
	 * than assumed, since what a vanilla chest reports through a capability wrapper is the platform's
	 * business and differs between loaders. The contract under test is that the interface promises
	 * exactly this, whatever it happens to be.
	 *
	 * Measured through [roomFor], the same question the interface itself asks, rather than asserted
	 * as a literal: on a part-filled container that number is upstream's own (over-reported) answer
	 * - see [FilterBatchGameTest.testASimulatedInsertOverReportsOnAPartFilledContainer]. What is
	 * under test is that the interface promises *exactly* what it was told, whatever that is.
	 */
	private fun GameTestHelper.layOutNearlyFullFarSide(freeSpace: Int): Triple<MultipartBlockEntity, InterfaceHookState, Long> {
		val interfacePos = BlockPos(0, 2, 1)
		val pipePos = BlockPos(0, 2, 2)
		val chestPos = BlockPos(0, 2, 3)

		setBlock(interfacePos, BlockRegistry.Multipart.defaultBlockState())
		val tile = getBlockEntity(interfacePos) as MultipartBlockEntity
		tile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val state = tile.hooks.getOrPut(Direction.NORTH.name) { InterfaceHookType.createState() } as InterfaceHookState

		setBlock(pipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(chestPos, Blocks.CHEST.defaultBlockState())

		// Every slot full of something else bar one, which holds all but [freeSpace] of a stack of
		// diamonds - so the chest will take exactly that many more and not one item beyond.
		val chest = getBlockEntity(chestPos) as ChestBlockEntity
		for (slot in 0 until chest.containerSize) chest.setItem(slot, ItemStack(Items.COBBLESTONE, 64))
		chest.setItem(0, ItemStack(Items.DIAMOND, 64 - freeSpace))

		for (pos in listOf(interfacePos, pipePos)) {
			val absolute = absolutePos(pos)
			level.setBlock(absolute, Block.updateFromNeighbourShapes(level.getBlockState(absolute), level, absolute), Block.UPDATE_ALL)
		}

		// The face the delivery actually lands on: opposite its last hop, pipe -> chest.
		val farStorage = ItemApi.BLOCK.find(level as ServerLevel, absolutePos(chestPos), Direction.NORTH)
		val farCapacity = farStorage?.roomFor(ItemResource.of(ItemStack(Items.DIAMOND)), 64) ?: 0L
		return Triple(tile, state, farCapacity)
	}

	/**
	 * The simulated insert a pusher sizes its push by. Reporting 64 here is what set the whole
	 * failure in motion.
	 */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testTheInterfaceOnlySimulatesWhatTheFarSideCanTake() {
		val (tile, state, farCapacity) = layOutNearlyFullFarSide(freeSpace = 3)
		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))

		runAfterDelay(20) {
			assertTrue(farCapacity in 1 until 64) {
				"This test needs a far side that is nearly, but not completely, full - it reports $farCapacity"
			}
			val accepted = state.exposedItemStorage(tile)!!.insert(diamond, 64, true)
			assertTrue(accepted == farCapacity) {
				"Expected the interface to promise exactly what the far chest will take ($farCapacity), got $accepted"
			}
			succeed()
		}
	}

	/** And the real insert ships only that much, rather than sending 64 for 3 to land and 61 to stall at the chest forever. */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testTheInterfaceOnlyShipsWhatTheFarSideCanTake() {
		val (tile, state, farCapacity) = layOutNearlyFullFarSide(freeSpace = 3)
		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))

		runAfterDelay(20) {
			val accepted = state.exposedItemStorage(tile)!!.insert(diamond, 64, false)
			assertTrue(accepted == farCapacity) {
				"Expected the interface to accept only the $farCapacity the far side will take, got $accepted - the rest would strand at the destination"
			}
			assertTrue(tile.travelingItems.sumOf { it.stack.amount } == farCapacity) {
				"Expected exactly 3 to have been dispatched, got ${tile.travelingItems.map { it.stack.amount }}"
			}
			succeed()
		}
	}

	/** A far side with no room at all is a refusal, not a zero-length shipment. */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testAFullFarSideIsRefusedOutright() {
		val (tile, state, _) = layOutNearlyFullFarSide(freeSpace = 0)
		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))

		runAfterDelay(20) {
			assertTrue(state.exposedItemStorage(tile)!!.insert(diamond, 64, true) == 0L) {
				"Expected a full far side to refuse the push outright"
			}
			assertTrue(state.exposedItemStorage(tile)!!.insert(diamond, 64, false) == 0L) {
				"Expected a real insert into a full far side to be refused too"
			}
			assertTrue(tile.travelingItems.isEmpty()) {
				"Expected nothing to have been dispatched into a full far side, got ${tile.travelingItems.size}"
			}
			succeed()
		}
	}
}
