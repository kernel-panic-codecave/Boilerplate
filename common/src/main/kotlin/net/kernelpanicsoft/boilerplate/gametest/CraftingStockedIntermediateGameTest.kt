package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.crafting.CraftStep
import net.kernelpanicsoft.boilerplate.crafting.CraftingBufferJob
import net.kernelpanicsoft.boilerplate.crafting.Pattern
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.kernelpanicsoft.boilerplate.resource.ResourceIdentity
import net.kernelpanicsoft.boilerplate.resource.resourceCell
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * A cluster keeps attracting an intermediate it is still short of, even once it has passed on more
 * of it than its own steps will ever produce.
 *
 * The failure this pins was found in a real base and is invisible in any small plan: an intermediate
 * that comes **partly from stock and partly from crafting**. Sizing the demand by what the job's own
 * steps *produce* works right up until the stock share is the larger one - then the job receives and
 * feeds onward more than its steps make, the counter goes negative partway through, the cluster
 * stops advertising for it, and the router files the craft's own output into storage while the job
 * starves on it. Observed as 304 Infused Alloy needed, 179 from stock, and the cluster giving up
 * after 125.
 *
 * Driven directly against [CraftingBufferJob] rather than through a built base: the arithmetic is
 * the whole bug, and a world large enough to have a part-stocked intermediate is not.
 */
@Suppress("unused")
class CraftingStockedIntermediateGameTest {

	@GameTest(template = SMALL, timeoutTicks = 20)
	fun GameTestHelper.testAPartlyStockedIntermediateIsStillAttractedAfterItsStepsAreAccountedFor() {
		val alloy = ItemResource.of(Items.IRON_INGOT)
		val alloyKey = ResourceIdentity.of(alloy)
		val target = ItemResource.of(Items.IRON_BLOCK)

		// 304 of the intermediate consumed, only 125 of it crafted - the other 179 come from stock.
		val consumes = Pattern(
			inputs = List(304) { ItemStack(Items.IRON_INGOT).resourceCell },
			outputs = listOf(ItemStack(Items.IRON_BLOCK).resourceCell),
			kind = PatternKind.PROCESSING,
		)
		val produces = Pattern(
			inputs = listOf(ItemStack(Items.RAW_IRON).resourceCell),
			outputs = listOf(ItemStack(Items.IRON_INGOT).resourceCell),
			kind = PatternKind.PROCESSING,
		)
		val job = CraftingBufferJob(
			"t", target, 1,
			listOf(CraftStep(produces, 125, alloy), CraftStep(consumes, 1, target)),
		)
		job.stockPlanned[alloyKey] = 179L
		job.outstandingStockClaims[alloyKey] = 0L

		assertTrue(job.outstandingOutput(alloy, 0L) == 125L) {
			"Expected the cluster to want all 125 its own steps will make, got ${job.outstandingOutput(alloy, 0L)}"
		}

		// Fed on more than the steps produce, because 179 of it came from stock. The cluster is
		// still short of 57 crafted ones and must keep attracting them.
		job.fedAmounts[0 to alloyKey] = 247L
		assertTrue(job.outstandingOutput(alloy, 0L) == 57L) {
			"Expected 57 crafted ones still outstanding once stock is discounted, got " +
				"${job.outstandingOutput(alloy, 0L)} - a negative here is the cluster refusing its own output"
		}

		// ...and it does stop once its steps have genuinely delivered, so the window stays narrow.
		job.fedAmounts[0 to alloyKey] = 304L
		assertTrue(job.outstandingOutput(alloy, 0L) == 0L) {
			"Expected nothing outstanding once all 125 crafted ones have arrived, got ${job.outstandingOutput(alloy, 0L)}"
		}

		// A resource no step produces is never attracted, however much of it the job consumes -
		// attraction runs at an unbeatable priority and must not cover ingredients.
		assertTrue(job.outstandingOutput(ItemResource.of(Items.RAW_IRON), 0L) == 0L) {
			"Expected an input the job merely consumes to attract nothing"
		}
		succeed()
	}
}
