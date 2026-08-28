package net.kernelpanicsoft.boilerplate.gametest

import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.power.PressureTankEncasementState
import net.kernelpanicsoft.boilerplate.power.PressureTankEncasementType
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper

/**
 * GameTest coverage for [net.kernelpanicsoft.boilerplate.power.network.PressurePipeNetworkManager]'s
 * own per-tick equalization pass: a full tank and an empty one, connected by a pressure pipe, level
 * toward the same fill fraction over time.
 */
@Suppress("unused")
class PressureEqualizationGameTest {
	@GameTest(template = SMALL, timeoutTicks = 400)
	fun GameTestHelper.testTwoTanksEqualizeAcrossAPressurePipe() {
		val tankAPos = BlockPos(0, 2, 0)
		val pipePos = BlockPos(0, 2, 1)
		val tankBPos = BlockPos(0, 2, 2)

		setBlock(tankAPos, BlockRegistry.Multipart.defaultBlockState())
		setBlock(pipePos, BlockRegistry.PressurePipe.defaultBlockState())
		setBlock(tankBPos, BlockRegistry.Multipart.defaultBlockState())

		val tankA = getBlockEntity(tankAPos) as MultipartBlockEntity
		val tankB = getBlockEntity(tankBPos) as MultipartBlockEntity
		tankA.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.PressurePipe)
		tankB.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.PressurePipe)
		val stateA = PressureTankEncasementType.createState()
		val stateB = PressureTankEncasementType.createState()
		tankA.encasement.value = stateA
		tankB.encasement.value = stateB
		stateA.pressure.insert(PressureTankEncasementState.CAPACITY, false)

		runAfterDelay(60) {
			val amountA = stateA.pressure.storedAmount
			val amountB = stateB.pressure.storedAmount
			assertTrue(amountB > 3000) { "Expected the empty tank to have filled substantially via equalization, got $amountB" }
			assertTrue(amountA < 7000) { "Expected the full tank to have drained substantially via equalization, got $amountA" }
			succeed()
		}
	}
}
