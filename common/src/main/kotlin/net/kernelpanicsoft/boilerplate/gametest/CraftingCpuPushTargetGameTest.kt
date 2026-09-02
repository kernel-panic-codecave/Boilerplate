package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.crafting.CraftStep
import net.kernelpanicsoft.boilerplate.crafting.CraftingResolver
import net.kernelpanicsoft.boilerplate.crafting.Pattern
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.kernelpanicsoft.boilerplate.pipe.network.ItemPipeRouter
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.util.resourceCell
import net.kernelpanicsoft.boilerplate.util.resourceStack
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks

/**
 * A Crafting CPU mid-job is a **push-routing destination** for whatever its steps are still short
 * of, and outranks every ordinary destination while it is.
 *
 * A CPU rides on a pipe segment, so push routing ordinarily walks straight through it and never
 * weighs it as a destination - which is why a machine's output, extracted by anything other than
 * the CPU's own drain, used to sail past its own job and land in whatever chest or warehouse the
 * network would take it. That is the bug these tests pin.
 */
@Suppress("unused")
class CraftingCpuPushTargetGameTest {
	private fun processingPattern(output: ItemStack, vararg inputs: ItemStack): Pattern = Pattern(
		inputs = inputs.map { it.resourceCell },
		outputs = listOf(output.resourceCell),
		kind = PatternKind.PROCESSING,
	)

	/** Places a pipe run ending at a chest, with a CPU on the run, and returns (push origin, cpu pos, chest pos). */
	private fun GameTestHelper.layOutRun(): Triple<BlockPos, BlockPos, BlockPos> {
		val originPos = BlockPos(0, 2, 0)
		val cpuPos = BlockPos(0, 2, 1)
		val chestPos = BlockPos(0, 2, 2)
		setBlock(originPos, BlockRegistry.Pipe.defaultBlockState())
		placeCraftingBuffer(cpuPos)
		setBlock(chestPos, Blocks.CHEST.defaultBlockState())
		return Triple(originPos, cpuPos, chestPos)
	}

	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testAnAwaitingCpuOutranksAnOrdinaryDestination() {
		val (originPos, cpuPos, _) = layOutRun()
		val ingot = ItemResource.of(ItemStack(Items.IRON_INGOT))
		val block = ItemResource.of(ItemStack(Items.IRON_BLOCK))

		val cpu = (getBlockEntity(cpuPos) as net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity).craftingBuffer
		// One step producing iron blocks, nothing delivered yet - so the cluster is short of them.
		cpu.enqueue(
			CraftingResolver.Plan(
				target = block,
				targetAmount = 1,
				steps = listOf(CraftStep(processingPattern(ItemStack(Items.IRON_BLOCK), ItemStack(Items.IRON_INGOT)), 1, block)),
				stockPulls = emptyMap(),
			)
		)

		runAfterDelay(5) {
			val serverLevel = level as ServerLevel
			val from = absolutePos(originPos)

			val awaited = ItemPipeRouter.findRoute(serverLevel, from, block)
			assertTrue(awaited?.lastOrNull() == absolutePos(cpuPos)) {
				"Expected the iron block to route into the CPU that is still short of it, got $awaited"
			}

			// A resource no step produces must still sail past the CPU to the chest, or the CPU
			// would be hoovering up unrelated items - the exact thing its exclusion prevents.
			val unrelated = ItemPipeRouter.findRoute(serverLevel, from, ingot)
            assertTrue(unrelated?.lastOrNull() != absolutePos(cpuPos)) {
                "Expected a resource the CPU is not waiting on to route past it, got $unrelated"
            }
			succeed()
		}
	}

	/** Once the cluster already holds what the step owed, it stops attracting it - the window is exactly the shortfall, not "forever while a job exists". */
	@GameTest(template = SMALL, timeoutTicks = 200)
	fun GameTestHelper.testACpuStopsAttractingOnceItsStepIsSatisfied() {
		val (originPos, cpuPos, _) = layOutRun()
		val block = ItemResource.of(ItemStack(Items.IRON_BLOCK))

		val cpuTile = getBlockEntity(cpuPos) as net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
		val cpu = cpuTile.craftingBuffer
		cpu.enqueue(
			CraftingResolver.Plan(
				target = block,
				targetAmount = 1,
				steps = listOf(CraftStep(processingPattern(ItemStack(Items.IRON_BLOCK), ItemStack(Items.IRON_INGOT)), 1, block)),
				stockPulls = emptyMap(),
			)
		)
		// The step's whole output already sitting in the pool - nothing further is owed.
		cpu.localStorage.insert(block, 1, false)

		runAfterDelay(5) {
			val serverLevel = level as ServerLevel
			val route = ItemPipeRouter.findRoute(serverLevel, absolutePos(originPos), block)
			assertTrue(route?.lastOrNull() != absolutePos(cpuPos)) {
				"Expected a satisfied step to stop attracting its own output, got $route"
			}
			succeed()
		}
	}
}
