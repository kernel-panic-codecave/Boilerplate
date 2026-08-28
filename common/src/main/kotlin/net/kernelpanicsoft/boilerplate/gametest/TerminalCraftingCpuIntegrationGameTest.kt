package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.crafting.CraftingRequest
import net.kernelpanicsoft.boilerplate.crafting.CraftingResolver
import net.kernelpanicsoft.boilerplate.crafting.SubmittedJobRef
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookType
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.warehouse.Bounds
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity
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
 * GameTest coverage for a terminal's own thin polling of a job it handed off to a Crafting CPU
 * cluster - [net.kernelpanicsoft.boilerplate.pipe.hook.advanceTerminalJobs] mirrors
 * [net.kernelpanicsoft.boilerplate.pipe.hook.MultipartBlockEntity.craftJobStatus] from whatever the
 * CPU itself reports, and drops the [SubmittedJobRef] once done - the terminal never drives
 * execution or receives the result directly, matching how [net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookMenu.submitCraft]
 * hands a resolved plan off in the first place. See `docs/design/m4-crafting-automation.md`.
 */
@Suppress("unused")
class TerminalCraftingCpuIntegrationGameTest {
	@GameTest(template = SMALL, timeoutTicks = 900)
	fun GameTestHelper.testTerminalPollsAndDropsTheRefOnceTheCpuFinishes() {
		val rackPos = BlockPos(0, 2, 3)
		val controllerPos = BlockPos(1, 2, 3)
		val feedPipePos = BlockPos(2, 2, 3)
		val hubPipePos = BlockPos(2, 2, 4)
		val cpuPos = BlockPos(3, 2, 4)
		val terminalPos = BlockPos(2, 2, 5)

		setBlock(rackPos, Blocks.CHEST.defaultBlockState())
		setBlock(controllerPos, BlockRegistry.WarehouseController.defaultBlockState())
		setBlock(feedPipePos, BlockRegistry.Pipe.defaultBlockState())
		setBlock(hubPipePos, BlockRegistry.Pipe.defaultBlockState())
		val cpuTile = placeCraftingBuffer(cpuPos)
		placeCreativePressureSource(cpuPos.above())

		setBlock(terminalPos, BlockRegistry.Multipart.defaultBlockState())
		val terminal = getBlockEntity(terminalPos) as MultipartBlockEntity
		terminal.pipeBlockId = BuiltInRegistries.BLOCK.getKey(BlockRegistry.Pipe)
		val terminalState = terminal.hooks.getOrPut(Direction.NORTH.name) { TerminalHookType.createState() } as TerminalHookState

		(getBlockEntity(rackPos) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 4))
		val controller = getBlockEntity(controllerPos) as WarehouseControllerBlockEntity
		controller.bounds = Bounds.of(absolutePos(rackPos), absolutePos(controllerPos))

		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))

		runAfterDelay(20) {
			val serverLevel = level as ServerLevel
			// Mirrors AbstractTerminalHookMenu.submitCraft's own logic exactly (resolve, then hand
			// off to a reachable Crafting CPU) - exercised directly here instead of through a real
			// menu, which a GameTest has no easy way to construct.
			val result = CraftingRequest.resolve(serverLevel, terminal.blockPos, diamond, 4)
			assertTrue(result is CraftingResolver.Result.Success) { "Expected the diamonds to resolve straight from stock, got $result" }
			val cpu = cpuTile.craftingBuffer
			val jobId = cpu.enqueue((result as CraftingResolver.Result.Success).plan)
			terminalState.submittedJobs += SubmittedJobRef(cpuTile.blockPos, jobId)
		}

		succeedWhen {
			assertTrue(terminalState.submittedJobs.isEmpty()) {
				"Expected the terminal to have dropped its own ref once the CPU's job finished, got ${terminalState.submittedJobs}"
			}
			val rack = getBlockEntity(rackPos) as ChestBlockEntity
			val diamondBackInRack = (0 until rack.containerSize).sumOf { if (rack.getItem(it).item == Items.DIAMOND) rack.getItem(it).count else 0 }
			assertTrue(diamondBackInRack >= 4) {
				"Expected the finished craft to have drained back into a reachable rack, not the terminal, got $diamondBackInRack in the rack"
			}
			assertTrue((0 until terminalState.output.size()).none { terminalState.output.getAmount(it) > 0 }) {
				"Expected the terminal's own output slots to have received nothing at all - results land back in storage, never the terminal"
			}
		}
	}
}
