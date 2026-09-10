package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.warehouse.Bounds
import net.kernelpanicsoft.boilerplate.warehouse.DeliveryTarget
import net.kernelpanicsoft.boilerplate.warehouse.entity.WarehouseControllerBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity

/** GameTest coverage for [WarehouseControllerBlockEntity.claimAndEnqueue]'s reservation ledger - two concurrent claims against the same rack stock can't double-count what the first already committed to. */
@Suppress("unused")
class WarehouseReservationGameTest {
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testASecondClaimCannotDoubleCountAFirstStillPendingOne() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val rackPos = BlockPos(3, 2, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 10))

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(cornerTwoPos))
		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))

		// Wait for the initial rescan to actually index the rack (see
		// WarehouseGameTest.testStaleIndexEntryDroppedAfterRackDepletedByHand's identical note) before
		// claiming against it.
		runAfterDelay(10) {
			val indexed = controller.index.slotsFor(diamond)
			assertTrue(indexed.size == 1 && indexed[0].amount == 10L) {
				"Expected the rack's 10 diamonds to be indexed before claiming, got $indexed"
			}

			val firstClaim = controller.claimAndEnqueue(diamond, 7, DeliveryTarget.Pipe(absolutePos(controllerPos)))
			val secondClaim = controller.claimAndEnqueue(diamond, 7, DeliveryTarget.Pipe(absolutePos(controllerPos)))
			assertTrue(firstClaim == 7L) { "Expected the first claim to get everything it asked for, got $firstClaim" }
			assertTrue(secondClaim == 3L) {
				"Expected the second claim to only get the 3 diamonds left unclaimed (10 - 7), not double-count the first claim's own 7, got $secondClaim"
			}
			succeed()
		}
	}
}
