package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
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
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.phys.Vec3

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

	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testControllerIndexesRacksInsideBoundsOnly() {
		val controllerPos = BlockPos(0, 2, 0)
		val insideChestPos = BlockPos(1, 2, 0)
		val outsideChestPos = BlockPos(3, 2, 3)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(insideChestPos, Blocks.CHEST.defaultBlockState())
		setBlock(outsideChestPos, Blocks.CHEST.defaultBlockState())

		(getBlockEntity(insideChestPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 5))
		(getBlockEntity(outsideChestPos) as ChestBlockEntity).setItem(0, ItemStack(Items.GOLD_INGOT, 3))

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(controllerPos), absolutePos(insideChestPos))

		succeedWhen {
			val diamondEntries = controller.index.locations[ItemResource.of(ItemStack(Items.DIAMOND))]
			assertTrue(diamondEntries != null && diamondEntries.size == 1 && diamondEntries[0].amount == 5L) {
				"Expected one indexed diamond entry with amount 5, got $diamondEntries"
			}
			assertTrue(controller.index.locations[ItemResource.of(ItemStack(Items.GOLD_INGOT))] == null) {
				"Expected the gold ingot outside the bound volume to not be indexed"
			}
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testGantryReachesMoveToTarget() {
		val controllerPos = BlockPos(0, 2, 0)
		val cornerOnePos = BlockPos(0, 2, 0)
		val cornerTwoPos = BlockPos(4, 3, 4)
		val targetPos = BlockPos(3, 2, 4)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(cornerOnePos), absolutePos(cornerTwoPos))
		controller.moveGantryTo(absolutePos(targetPos))

		succeedWhen {
			assertTrue(!controller.gantry.isMoving) { "Expected the gantry to have finished its move by now" }
			val expected = Vec3.atCenterOf(absolutePos(targetPos))
			assertTrue(controller.gantry.pos.distanceTo(expected) < 0.01) {
				"Expected the gantry to have arrived at $expected, got ${controller.gantry.pos}"
			}
		}
	}
}
