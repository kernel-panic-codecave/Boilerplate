package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.crafting.CraftingBufferJob
import net.kernelpanicsoft.boilerplate.crafting.CraftingResolver
import net.kernelpanicsoft.boilerplate.crafting.Pattern
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.kernelpanicsoft.boilerplate.resource.resourceCell
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * GameTest coverage for [CraftingBufferJob.toTree]/[CraftingBufferJob.stepStatus] - the server-side
 * data feeding [net.kernelpanicsoft.boilerplate.pipe.gui.CraftingTreeView]'s node graph.
 * Pure-function logic over a hand-built [CraftingBufferJob], matching [CraftingResolverGameTest]'s
 * own approach.
 */
@Suppress("unused")
class CraftingBufferJobGameTest {
	private fun pattern(output: ItemStack, vararg inputs: ItemStack): Pattern = Pattern(
		inputs = inputs.map { it.resourceCell },
		outputs = listOf(output.resourceCell),
		kind = PatternKind.PROCESSING,
	)

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testToTreeNestsAStepsChildUnderItsConsumer() {
		val ironBlock = ItemResource.of(ItemStack(Items.IRON_BLOCK))
		val ironIngot = ItemResource.of(ItemStack(Items.IRON_INGOT))
		val rawIron = ItemResource.of(ItemStack(Items.RAW_IRON))
		val blockPattern = pattern(ItemStack(Items.IRON_BLOCK), ItemStack(Items.IRON_INGOT), ItemStack(Items.IRON_INGOT))
		val ingotPattern = pattern(ItemStack(Items.IRON_INGOT, 9), ItemStack(Items.RAW_IRON))

		val result = CraftingResolver.resolve(
			target = ironBlock,
			amount = 1,
			stockOf = { resource -> if (resource == rawIron) 64 else 0 },
			patternFor = { resource -> if (resource == ironBlock) blockPattern else if (resource == ironIngot) ingotPattern else null },
		) as CraftingResolver.Result.Success

		val job = CraftingBufferJob("0", ironBlock, 1, result.plan.steps)
		val tree = job.toTree()

		assertTrue(tree != null) { "Expected a non-null tree for a job with steps" }
		assertTrue(tree!!.resource == ironBlock) { "Expected the root node to be the target resource, got ${tree.resource}" }
		assertTrue(tree.children.size == 1 && tree.children[0].resource == ironIngot) {
			"Expected the iron block node to have a single iron ingot child, got ${tree.children}"
		}
		assertTrue(tree.children[0].children.isEmpty()) { "Expected the iron ingot node to have no further children (raw iron came from stock), got ${tree.children[0].children}" }
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testToTreeIsNullWhenNothingNeedsCrafting() {
		val ironBlock = ItemResource.of(ItemStack(Items.IRON_BLOCK))
		val job = CraftingBufferJob("0", ironBlock, 1, emptyList())
		assertTrue(job.toTree() == null) { "Expected a job with no craft steps (fulfilled straight from stock) to have no tree" }
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testStepStatusTracksProgress() {
		val ironBlock = ItemResource.of(ItemStack(Items.IRON_BLOCK))
		val ironIngot = ItemResource.of(ItemStack(Items.IRON_INGOT))
		val blockPattern = pattern(ItemStack(Items.IRON_BLOCK), ItemStack(Items.IRON_INGOT), ItemStack(Items.IRON_INGOT))
		val result = CraftingResolver.resolve(
			target = ironBlock,
			amount = 1,
			stockOf = { resource -> if (resource == ironIngot) 2 else 0 },
			patternFor = { if (it == ironBlock) blockPattern else null },
		) as CraftingResolver.Result.Success

		val job = CraftingBufferJob("0", ironBlock, 1, result.plan.steps)
		assertTrue(job.stepStatus(0) == "Waiting for a pattern provider") { "Expected an unassigned step to report waiting, got ${job.stepStatus(0)}" }

		job.tableForStep[0] = BlockPos.ZERO
		assertTrue(job.stepStatus(0) == "Feeding ingredients…") { "Expected an assigned-but-unfed step to report feeding, got ${job.stepStatus(0)}" }

		for ((resource, perRun) in blockPattern.requiredInputs()) job.fedAmounts[0 to resource] = perRun * job.steps[0].runs
		assertTrue(job.stepStatus(0) == "Processing…") { "Expected a fully-fed step to report processing, got ${job.stepStatus(0)}" }

		job.done = true
		assertTrue(job.stepStatus(0) == "Delivered") { "Expected a done job's own step to report delivered, got ${job.stepStatus(0)}" }
		succeed()
	}
}
