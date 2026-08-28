package net.kernelpanicsoft.boilerplate.gametest

import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.archie.transfer.ArchieEnergyStorage
import net.kernelpanicsoft.boilerplate.power.PressureLine
import net.kernelpanicsoft.boilerplate.power.PressureTankEncasementType
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel

/**
 * GameTest coverage for [WarehouseControllerBlockEntity]'s real [PressureLine] wiring
 * ([WarehouseControllerBlockEntity.pressureSpeedMultiplier]): with a full tank adjacent, its own
 * [net.kernelpanicsoft.boilerplate.power.PressureConsumer.onPressureTick] genuinely returns a
 * speed bonus above `1.0`x, and with nothing reachable it returns `0.0` - a hard gate, not a floor;
 * the gantry simply doesn't move that tick.
 */
@Suppress("unused")
class WarehousePressureScalingGameTest {
	@GameTest(template = SMALL, timeoutTicks = 100)
	fun GameTestHelper.testAFullAdjacentTankGivesTheControllerASpeedBonus() {
		val controllerPos = BlockPos(0, 2, 0)
		val tankPos = BlockPos(0, 2, 1)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(tankPos, BlockRegistry.Multipart.defaultBlockState())

		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		val tank = getBlockEntity(tankPos) as MultipartBlockEntity
		tank.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.PressurePipe)
		val tankState = PressureTankEncasementType.createState()
		tank.encasement.value = tankState
		tankState.pressure.insert(10_000, false)

		runAfterDelay(20) {
			val serverLevel = level as ServerLevel
			val line = PressureLine.find(serverLevel, controller.blockPos)
			assertTrue(line != null) { "Expected the adjacent tank to be found as the controller's own pressure line" }
			val multiplier = controller.onPressureTick(line!!)
			assertTrue(multiplier > 1.0) { "Expected a full adjacent tank to give a real speed bonus above the 1.0x baseline, got ${multiplier}x" }
			succeed()
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testNoReachablePressureHardGatesTheController() {
		val controllerPos = BlockPos(0, 2, 0)
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity

		runAfterDelay(5) {
			val serverLevel = level as ServerLevel
			assertTrue(PressureLine.find(serverLevel, controller.blockPos) == null) { "Expected no pressure line to be reachable with nothing placed nearby" }
			val multiplier = controller.onPressureTick(ArchieEnergyStorage(0))
			assertTrue(multiplier == 0.0) { "Expected the controller to be hard-gated with nothing reachable, got ${multiplier}x" }
			succeed()
		}
	}
}
