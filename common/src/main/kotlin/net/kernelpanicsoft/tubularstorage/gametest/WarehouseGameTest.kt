package net.kernelpanicsoft.tubularstorage.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.kernelpanicsoft.tubularstorage.warehouse.Bounds
import net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameType

/** GameTest coverage for [net.kernelpanicsoft.tubularstorage.warehouse.WarehouseWandItem]'s bind flow. */
@Suppress("unused")
class WarehouseGameTest {
	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testWandBindsControllerToClickedCorners() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerOnePos = BlockPos(0, 2, 1)
		val cornerTwoPos = BlockPos(3, 4, 2)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())

		val player = makeMockPlayer(GameType.CREATIVE)
		player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(ItemRegistry.WarehouseWand))

		useBlock(cornerOnePos, player)
		useBlock(cornerTwoPos, player)
		useBlock(controllerPos, player)

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		val expected = Bounds.of(absolutePos(cornerOnePos), absolutePos(cornerTwoPos))
		assertTrue(controller.bounds == expected) {
			"Expected the controller to have bound $expected, got ${controller.bounds}"
		}

		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testWandRestartsSelectionAfterBinding() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerOnePos = BlockPos(0, 2, 1)
		val cornerTwoPos = BlockPos(1, 2, 1)
		val nextCornerPos = BlockPos(2, 2, 1)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())

		val player = makeMockPlayer(GameType.CREATIVE)
		player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(ItemRegistry.WarehouseWand))

		useBlock(cornerOnePos, player)
		useBlock(cornerTwoPos, player)
		useBlock(controllerPos, player)
		val boundAfterFirstBind = (getBlockEntity(controllerPos) as WarehouseControllerBlockEntity).bounds

		// A fourth click, with the wand's selection already cleared by the bind above, must start a
		// fresh selection rather than immediately rebinding the controller against stale corners.
		useBlock(nextCornerPos, player)
		useBlock(controllerPos, player)
		val boundAfterStrayClick = (getBlockEntity(controllerPos) as WarehouseControllerBlockEntity).bounds

		assertTrue(boundAfterStrayClick == boundAfterFirstBind) {
			"Expected a single stray click after binding to leave the controller's bounds ($boundAfterFirstBind) unchanged, got $boundAfterStrayClick"
		}

		succeed()
	}
}
