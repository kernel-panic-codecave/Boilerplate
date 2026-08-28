package net.kernelpanicsoft.tubularstorage.gametest

import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.power.PressureLine
import net.kernelpanicsoft.tubularstorage.power.PressureTankEncasementType
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel

/** GameTest coverage for [PressureLine.find]: it reaches a tank across a multi-segment pressure-pipe run, across a plain item-pipe run just as well, and returns `null` when nothing is reachable at all. */
@Suppress("unused")
class PressureLineGameTest {
	@GameTest(template = SMALL, timeoutTicks = 100)
	fun GameTestHelper.testFindsTheTankAcrossAMultiSegmentPressurePipeRun() {
		val consumerPos = BlockPos(0, 2, 0)
		val pipe1Pos = BlockPos(0, 2, 1)
		val pipe2Pos = BlockPos(0, 2, 2)
		val pipe3Pos = BlockPos(0, 2, 3)
		val tankPos = BlockPos(0, 2, 4)

		setBlock(pipe1Pos, BlockRegistry.PressurePipe.defaultBlockState())
		setBlock(pipe2Pos, BlockRegistry.PressurePipe.defaultBlockState())
		setBlock(pipe3Pos, BlockRegistry.PressurePipe.defaultBlockState())
		setBlock(tankPos, BlockRegistry.Multipart.defaultBlockState())
		val tile = getBlockEntity(tankPos) as MultipartBlockEntity
		tile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.PressurePipe)
		val state = PressureTankEncasementType.createState()
		tile.encasement.value = state
		state.pressure.insert(1234, false)

		runAfterDelay(20) {
			val serverLevel = level as ServerLevel
			val line = PressureLine.find(serverLevel, absolutePos(consumerPos))
			assertTrue(line != null) { "Expected a pressure line to be found reaching across the pipe run to the tank" }
			assertTrue(line === state.pressure) { "Expected the found line to be the tank's own storage" }
			succeed()
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 100)
	fun GameTestHelper.testFindsTheTankAcrossAPlainItemPipeRun() {
		val consumerPos = BlockPos(0, 2, 0)
		val itemPipe1Pos = BlockPos(0, 2, 1)
		val itemPipe2Pos = BlockPos(0, 2, 2)
		val tankPos = BlockPos(0, 2, 3)

		// A plain item-pipe segment - no PressurePipeBlock in this run at all - to prove item pipes
		// conduct pressure on their own, not just a dedicated pressure-pipe run.
		setBlock(consumerPos, BlockRegistry.Multipart.defaultBlockState())
		val consumerTile = getBlockEntity(consumerPos) as MultipartBlockEntity
		consumerTile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)

		setBlock(itemPipe1Pos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(itemPipe2Pos, BlockRegistry.Pipe.defaultBlockState())

		setBlock(tankPos, BlockRegistry.Multipart.defaultBlockState())
		val tankTile = getBlockEntity(tankPos) as MultipartBlockEntity
		tankTile.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.PressurePipe)
		val tankState = PressureTankEncasementType.createState()
		tankTile.encasement.value = tankState
		tankState.pressure.insert(1234, false)

		runAfterDelay(20) {
			val serverLevel = level as ServerLevel
			val line = PressureLine.find(serverLevel, absolutePos(consumerPos))
			assertTrue(line != null) { "Expected a pressure line to be found reaching across a plain item-pipe run to the tank - item pipes should conduct pressure too" }
			assertTrue(line === tankState.pressure) { "Expected the found line to be the tank's own storage" }
			succeed()
		}
	}

	@GameTest(template = SMALL, timeoutTicks = 40)
	fun GameTestHelper.testReturnsNullWhenNoPressureNetworkIsReachable() {
		val consumerPos = BlockPos(0, 2, 0)
		runAfterDelay(5) {
			val serverLevel = level as ServerLevel
			val line = PressureLine.find(serverLevel, absolutePos(consumerPos))
			assertTrue(line == null) { "Expected no pressure line to be found with nothing placed nearby" }
			succeed()
		}
	}
}
