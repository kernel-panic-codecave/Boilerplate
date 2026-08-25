package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.crafting.CraftingRequest
import net.kernelpanicsoft.tubularstorage.crafting.CraftingResolver
import net.kernelpanicsoft.tubularstorage.crafting.Pattern
import net.kernelpanicsoft.tubularstorage.crafting.PatternItemData
import net.kernelpanicsoft.tubularstorage.crafting.PatternKind
import net.kernelpanicsoft.tubularstorage.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookType
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.kernelpanicsoft.tubularstorage.warehouse.Bounds
import net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity

/**
 * GameTest coverage for [net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferEncasementState.cancelJob] -
 * the server-side operation behind the Crafting Buffer menu's own Cancel buttons
 * ([net.kernelpanicsoft.tubularstorage.crafting.gui.CraftingBufferScreen]).
 */
@Suppress("unused")
class CraftingBufferCancelGameTest {
	/** Mirrors [CraftingBufferBacklogGameTest]'s own two-stock-only-jobs setup, but cancels the queued one instead of letting it run. */
	@GameTest(template = SMALL, timeoutTicks = 900)
	fun GameTestHelper.testCancellingAQueuedJobDropsItWithoutEverClaimingIt() {
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
			val emeraldJobId = cpu.enqueue((emeraldResult as CraftingResolver.Result.Success).plan)

			assertTrue(cpu.cancelJob(emeraldJobId)) { "Expected cancelJob to find and drop the still-queued emerald job" }
			assertTrue(cpu.backlogDepth() == 1) { "Expected only the active diamond job to remain right after cancelling the queued one, got backlogDepth=${cpu.backlogDepth()}" }
		}

		succeedWhen {
			val diamondBackInRack = (0 until rack.containerSize).sumOf { if (rack.getItem(it).item == Items.DIAMOND) rack.getItem(it).count else 0 }
			val emeraldStillInRack = (0 until rack.containerSize).sumOf { if (rack.getItem(it).item == Items.EMERALD) rack.getItem(it).count else 0 }
			assertTrue(diamondBackInRack >= 2) { "Expected the still-active diamond job to have finished and drained normally, got $diamondBackInRack" }
			assertTrue(emeraldStillInRack == 3) { "Expected the cancelled emerald job to have never claimed anything - all 3 should still be sitting untouched in the rack, got $emeraldStillInRack" }
			assertTrue(cpu.backlogDepth() == 0) { "Expected the cluster to be fully idle once the surviving job finished, got backlogDepth=${cpu.backlogDepth()}" }
		}
	}

	/** Mirrors [CraftingBufferGameTest.testSingleStepCraftDrainsIntoAReachableRackNotTheSubmitter]'s own setup, but cancels mid-flight (after ingots reached the machine, before it ever "finishes") instead of simulating completion. */
	@GameTest(template = MEDIUM, timeoutTicks = 1400)
	fun GameTestHelper.testCancellingTheActiveJobDrainsWhatWasAlreadyClaimedWithoutCompletingIt() {
		val rackPos = BlockPos(0, 2, 0)
		val controllerPos = BlockPos(1, 2, 0)
		val feedPipePos = BlockPos(2, 2, 0)
		val hubPipePos = BlockPos(2, 2, 1)
		val cpuPos = BlockPos(2, 2, 2)
		val linkPipe3Pos = BlockPos(2, 2, 3)
		val patternHookPos = BlockPos(2, 2, 4)
		val machinePos = BlockPos(3, 2, 4)

		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(machinePos, Blocks.CHEST.defaultBlockState())

		setBlock(patternHookPos, BlockRegistry.Multipart.defaultBlockState())
		val patternHook = getBlockEntity(patternHookPos) as MultipartBlockEntity
		patternHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val patternHookState = patternHook.hooks.getOrPut(Direction.EAST.name) { PatternProviderHookType.createState() } as PatternProviderHookState
		val pattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.IRON_INGOT)), ItemResource.of(ItemStack(Items.IRON_INGOT))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.IRON_BLOCK)), 1)),
			kind = PatternKind.PROCESSING,
		)
		patternHookState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = pattern })

		setBlock(feedPipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(hubPipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(linkPipe3Pos, BlockRegistry.Pipe.defaultBlockState())
		val cpuTile = placeCraftingBuffer(cpuPos)

		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.IRON_INGOT, 8))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))

		val cpu = cpuTile.craftingBuffer
		val target = ItemResource.of(ItemStack(Items.IRON_BLOCK))
		var jobId = ""

		runAfterDelay(20) {
			val result = CraftingRequest.resolve(level as ServerLevel, cpuTile.blockPos, target, 1)
			assertTrue(result is CraftingResolver.Result.Success) { "Expected the iron block to resolve to exactly one craft step, got $result" }
			jobId = cpu.enqueue((result as CraftingResolver.Result.Success).plan)
		}

		runAfterDelay(400) {
			val machine = getBlockEntity(machinePos) as ChestBlockEntity
			val ingotsDelivered = (0 until machine.containerSize).sumOf { if (machine.getItem(it).item == Items.IRON_INGOT) machine.getItem(it).count else 0 }
			assertTrue(ingotsDelivered >= 2) { "Expected the CPU to have already pushed 2 iron ingots into the machine before cancelling, got $ingotsDelivered" }
			// Deliberately never converted to an iron block here (unlike the test this one mirrors) -
			// cancelling now must stop the job cold rather than let it complete regardless.
			assertTrue(cpu.cancelJob(jobId)) { "Expected cancelJob to find the still-running job" }
		}

		succeedWhen {
			assertTrue(cpu.backlogDepth() == 0) { "Expected the cancelled job to have drained and cleared itself out, got backlogDepth=${cpu.backlogDepth()}" }
			val storage = cpu.combinedStorage(cpuTile)
			assertTrue((0 until storage.size()).none { storage.get(it).resource == target }) {
				"Expected the cancelled job to never have produced the iron block at all"
			}
		}
	}
}
