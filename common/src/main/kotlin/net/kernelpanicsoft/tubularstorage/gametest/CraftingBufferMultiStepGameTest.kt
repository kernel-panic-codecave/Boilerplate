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
 * GameTest coverage for a genuine multi-step Crafting CPU job: logs resolve into an
 * intermediate (planks) via one machine, which then feeds a second step (sticks) on a *different*
 * machine - the cluster's own combined storage is the only thing carrying the intermediate between
 * the two, with no direct hook-to-hook delivery at all. Also exercises byproduct draining: planks
 * produced beyond what the sticks step actually needs get drained back to the network right
 * alongside the finished target. See `docs/design/m4-crafting-automation.md`.
 */
@Suppress("unused")
class CraftingBufferMultiStepGameTest {
	@GameTest(template = MEDIUM, timeoutTicks = 1800)
	fun GameTestHelper.testChainedIntermediateFlowsThroughTheCpuToASecondMachine() {
		val rackPos = BlockPos(0, 2, 3)
		val controllerPos = BlockPos(1, 2, 3)
		val feedPipePos = BlockPos(2, 2, 3)
		val hubPipePos = BlockPos(2, 2, 4)
		val cpuPos = BlockPos(3, 2, 4)
		val linkPipeAPos = BlockPos(2, 2, 5)
		val planksHookPos = BlockPos(2, 2, 6)
		val planksMachinePos = BlockPos(3, 2, 6)
		val linkPipeBPos = BlockPos(1, 2, 5)
		val sticksHookPos = BlockPos(1, 2, 6)
		val sticksMachinePos = BlockPos(0, 2, 6)

		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(planksMachinePos, Blocks.CHEST.defaultBlockState())
		setBlock(sticksMachinePos, Blocks.CHEST.defaultBlockState())

		setBlock(planksHookPos, BlockRegistry.Multipart.defaultBlockState())
		val planksHook = getBlockEntity(planksHookPos) as MultipartBlockEntity
		planksHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val planksHookState = planksHook.hooks.getOrPut(Direction.EAST.name) { PatternProviderHookType.createState() } as PatternProviderHookState
		val planksPattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_LOG))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.OAK_PLANKS)), 4)),
			kind = PatternKind.PROCESSING,
		)
		planksHookState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = planksPattern })

		setBlock(sticksHookPos, BlockRegistry.Multipart.defaultBlockState())
		val sticksHook = getBlockEntity(sticksHookPos) as MultipartBlockEntity
		sticksHook.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val sticksHookState = sticksHook.hooks.getOrPut(Direction.WEST.name) { PatternProviderHookType.createState() } as PatternProviderHookState
		val sticksPattern = Pattern(
			inputs = listOf(ItemResource.of(ItemStack(Items.OAK_PLANKS)), ItemResource.of(ItemStack(Items.OAK_PLANKS))),
			outputs = listOf(ResourceStack(ItemResource.of(ItemStack(Items.STICK)), 4)),
			kind = PatternKind.PROCESSING,
		)
		sticksHookState.patterns.get(0).set(ItemStack(ItemRegistry.Pattern).also { PatternItemData(it).pattern = sticksPattern })

		setBlock(feedPipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(hubPipePos, BlockRegistry.Pipe.defaultBlockState())
		val cpuTile = placeCraftingBuffer(cpuPos)
		placeCreativePressureSource(cpuPos.above())
		setBlock(linkPipeAPos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(linkPipeBPos, BlockRegistry.Pipe.defaultBlockState())

		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.OAK_LOG, 5))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))

		val cpu = cpuTile.craftingBuffer
		val target = ItemResource.of(ItemStack(Items.STICK))

		runAfterDelay(20) {
			val result = CraftingRequest.resolve(level as ServerLevel, absolutePos(hubPipePos), target, 4)
			assertTrue(result is CraftingResolver.Result.Success) { "Expected 4 sticks to resolve via an intermediate planks step, got $result" }
			val plan = (result as CraftingResolver.Result.Success).plan
			assertTrue(plan.steps.size == 2) { "Expected exactly 2 steps (planks, then sticks), got ${plan.steps.map { it.resource }}" }
			cpu.enqueue(plan)
		}

		runAfterDelay(400) {
			val planksMachine = getBlockEntity(planksMachinePos) as ChestBlockEntity
			val logsDelivered = (0 until planksMachine.containerSize).sumOf { if (planksMachine.getItem(it).item == Items.OAK_LOG) planksMachine.getItem(it).count else 0 }
			assertTrue(logsDelivered >= 1) { "Expected the CPU to have pushed at least 1 oak log into the planks machine by now, got $logsDelivered" }
			for (i in 0 until planksMachine.containerSize) planksMachine.setItem(i, ItemStack.EMPTY)
			planksMachine.setItem(0, ItemStack(Items.OAK_PLANKS, 4))
		}

		runAfterDelay(800) {
			val sticksMachine = getBlockEntity(sticksMachinePos) as ChestBlockEntity
			val planksDelivered = (0 until sticksMachine.containerSize).sumOf { if (sticksMachine.getItem(it).item == Items.OAK_PLANKS) sticksMachine.getItem(it).count else 0 }
			assertTrue(planksDelivered >= 2) { "Expected the CPU to have pulled the planks step's own output back and pushed 2 of them into the sticks machine by now, got $planksDelivered" }
			for (i in 0 until sticksMachine.containerSize) sticksMachine.setItem(i, ItemStack.EMPTY)
			sticksMachine.setItem(0, ItemStack(Items.STICK, 4))
		}

		succeedWhen {
			val rack = getBlockEntity(rackPos) as ChestBlockEntity
			val stickInRack = (0 until rack.containerSize).sumOf { if (rack.getItem(it).item == Items.STICK) rack.getItem(it).count else 0 }
			assertTrue(stickInRack >= 4) {
				"Expected the 4 finished sticks to have drained back into a reachable rack, got rack contents ${(0 until rack.containerSize).map { rack.getItem(it) }.filter { !it.isEmpty }}"
			}
			assertTrue(cpu.combinedStorage(cpuTile).let { storage -> (0 until storage.size()).none { !storage.get(it).resource.isBlank } }) {
				"Expected the Crafting CPU's own storage to have fully drained (target plus any leftover planks byproduct), not still be holding something"
			}
		}
	}
}
