package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.warehouse.Bounds
import net.kernelpanicsoft.boilerplate.warehouse.entity.WarehouseControllerBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity

/**
 * GameTest coverage for [RequestFulfillment.fulfillFromWarehouse]'s [WarehouseControllerBlockEntity.hasPressure]
 * check - previously, a request against a warehouse with no reachable pressure still got a retrieve
 * job enqueued, which then sat hard-gated at `0.0` gantry speed indefinitely (visible in-game as the
 * gantry stuttering in place). Now such a warehouse is skipped entirely, the same way a
 * [net.kernelpanicsoft.boilerplate.pipe.hook.PipeHookType] without enough pressure is skipped
 * rather than ticked - see `docs/design/m5-pressure-power.md`.
 */
@Suppress("unused")
class WarehousePressureGateGameTest {
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testAWarehouseWithoutPressureIsSkippedByRequestFulfillment() {
		val rackPos = BlockPos(0, 2, 0)
		val controllerPos = BlockPos(1, 2, 0)
		val pipePos = BlockPos(2, 2, 0)
		val destPos = BlockPos(2, 2, 1)
		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		// Deliberately no pressure source anywhere near controllerPos.
		setBlock(pipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))
		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))

		// Wait for the initial rescan to index the rack before requesting against it - see
		// WarehouseReservationGameTest's identical note.
		runAfterDelay(10) {
			val serverLevel = level as ServerLevel
			assertTrue(controller.index.slotsFor(diamond).firstOrNull()?.amount == 8L) {
				"Expected the rack's 8 diamonds to be indexed before requesting, got ${controller.index.slotsFor(diamond)}"
			}
			assertTrue(!controller.hasPressure()) { "Expected the controller to report no reachable pressure" }

			val dispatched = RequestFulfillment.request(serverLevel, absolutePos(pipePos), ResourceStack(diamond, 4), absolutePos(destPos))
			assertTrue(dispatched == 0L) { "Expected nothing to be dispatched from a warehouse with no reachable pressure, got $dispatched" }
			succeed()
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testAWarehouseWithPressureIsUsedByRequestFulfillment() {
		val rackPos = BlockPos(0, 2, 0)
		val controllerPos = BlockPos(1, 2, 0)
		val pipePos = BlockPos(2, 2, 0)
		val destPos = BlockPos(2, 2, 1)
		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		placeAdjacentPressureSource(controllerPos.below())
		setBlock(pipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(destPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 8))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))
		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))

		runAfterDelay(10) {
			val serverLevel = level as ServerLevel
			assertTrue(controller.index.slotsFor(diamond).firstOrNull()?.amount == 8L) {
				"Expected the rack's 8 diamonds to be indexed before requesting, got ${controller.index.slotsFor(diamond)}"
			}
			assertTrue(controller.hasPressure()) { "Expected the controller to report reachable pressure via the adjacent creative source" }

			val dispatched = RequestFulfillment.request(serverLevel, absolutePos(pipePos), ResourceStack(diamond, 4), absolutePos(destPos))
			assertTrue(dispatched == 4L) { "Expected the request to dispatch once pressure was reachable, got $dispatched" }
			succeed()
		}
	}
}
