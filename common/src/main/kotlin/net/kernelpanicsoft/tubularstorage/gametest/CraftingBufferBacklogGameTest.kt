package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.crafting.CraftingRequest
import net.kernelpanicsoft.tubularstorage.crafting.CraftingResolver
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.warehouse.Bounds
import net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity

/**
 * GameTest coverage for a Crafting CPU cluster's own backlog: a second job submitted while one is
 * already active queues rather than running alongside it, only starting (claiming its own stock)
 * once the first finishes and fully drains. Uses trivial stock-only jobs (no pattern/machine
 * involved at all) to keep the test focused purely on backlog sequencing. See
 * `docs/design/m4-crafting-automation.md`.
 */
@Suppress("unused")
class CraftingBufferBacklogGameTest {
	@GameTest(template = SMALL, timeoutTicks = 900)
	fun GameTestHelper.testASecondJobQueuesRatherThanRunningAlongsideTheFirst() {
		val rackPos = BlockPos(0, 2, 3)
		val controllerPos = BlockPos(1, 2, 3)
		val feedPipePos = BlockPos(2, 2, 3)
		val hubPipePos = BlockPos(2, 2, 4)
		val cpuPos = BlockPos(3, 2, 4)

		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(feedPipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(hubPipePos, BlockRegistry.Pipe.defaultBlockState())
		val cpuTile = placeCraftingBuffer(cpuPos)

		val rack = getBlockEntity(rackPos) as ChestBlockEntity
		rack.setItem(0, ItemStack(Items.DIAMOND, 2))
		rack.setItem(1, ItemStack(Items.EMERALD, 3))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))

		val cpu = cpuTile.craftingBuffer
		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))
		val emerald = ItemResource.of(ItemStack(Items.EMERALD))

		runAfterDelay(20) {
			val serverLevel = level as ServerLevel
			val diamondResult = CraftingRequest.resolve(serverLevel, absolutePos(hubPipePos), diamond, 2)
			assertTrue(diamondResult is CraftingResolver.Result.Success) { "Expected the diamonds to resolve straight from stock, got $diamondResult" }
			cpu.enqueue((diamondResult as CraftingResolver.Result.Success).plan)

			val emeraldResult = CraftingRequest.resolve(serverLevel, absolutePos(hubPipePos), emerald, 3)
			assertTrue(emeraldResult is CraftingResolver.Result.Success) { "Expected the emeralds to resolve straight from stock, got $emeraldResult" }
			cpu.enqueue((emeraldResult as CraftingResolver.Result.Success).plan)

			assertTrue(cpu.backlogDepth() == 2) { "Expected both jobs to be tracked (one active, one queued) right after submission, got backlogDepth=${cpu.backlogDepth()}" }
		}

		succeedWhen {
			val diamondBackInRack = (0 until rack.containerSize).sumOf { if (rack.getItem(it).item == Items.DIAMOND) rack.getItem(it).count else 0 }
			val emeraldBackInRack = (0 until rack.containerSize).sumOf { if (rack.getItem(it).item == Items.EMERALD) rack.getItem(it).count else 0 }
			assertTrue(diamondBackInRack >= 2) { "Expected the first (diamond) job to have eventually finished and drained, got $diamondBackInRack" }
			assertTrue(emeraldBackInRack >= 3) { "Expected the second (emerald) job to have eventually finished and drained too, got $emeraldBackInRack" }
			assertTrue(cpu.backlogDepth() == 0) { "Expected the cluster to be fully idle once both jobs finished, got backlogDepth=${cpu.backlogDepth()}" }
		}
	}
}
