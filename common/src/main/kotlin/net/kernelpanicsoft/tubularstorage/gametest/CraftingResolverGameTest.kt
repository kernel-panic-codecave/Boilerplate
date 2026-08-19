package net.kernelpanicsoft.tubularstorage.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.tubularstorage.crafting.CraftingResolver
import net.kernelpanicsoft.tubularstorage.crafting.Pattern
import net.kernelpanicsoft.tubularstorage.crafting.PatternKind
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * GameTest coverage for [CraftingResolver] - pure-function logic, so these don't touch the world at
 * all (matching [net.kernelpanicsoft.tubularstorage.warehouse.WarehouseScale]'s own identical
 * approach for a pure function), just hand-built patterns/stock against the algorithm directly.
 */
@Suppress("unused")
class CraftingResolverGameTest {
	private fun pattern(output: ItemStack, vararg inputs: ItemStack): Pattern = Pattern(
		inputs = inputs.map { ItemResource.of(it) },
		outputs = listOf(ResourceStack(ItemResource.of(output), output.count.toLong())),
		kind = PatternKind.PROCESSING,
	)

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testBasicTwoLevelResolutionPullsFromStock() {
		val ironBlock = ItemResource.of(ItemStack(Items.IRON_BLOCK))
		val ironIngot = ItemResource.of(ItemStack(Items.IRON_INGOT))
		val recipe = pattern(ItemStack(Items.IRON_BLOCK), ItemStack(Items.IRON_INGOT), ItemStack(Items.IRON_INGOT))

		val result = CraftingResolver.resolve(
			target = ironBlock,
			amount = 1,
			stockOf = { if (it == ironIngot) 2 else 0 },
			patternFor = { if (it == ironBlock) recipe else null },
		)

		assertTrue(result is CraftingResolver.Result.Success) { "Expected a resolvable plan, got $result" }
		val plan = (result as CraftingResolver.Result.Success).plan
		assertTrue(plan.stockPulls[ironIngot] == 2L) { "Expected 2 iron ingots pulled from stock, got ${plan.stockPulls}" }
		assertTrue(plan.steps.size == 1 && plan.steps[0].pattern == recipe && plan.steps[0].runs == 1L) {
			"Expected exactly one craft step running the recipe once, got ${plan.steps}"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testSharedSubResourceIsDedupedNotDoubleCounted() {
		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))
		val gold = ItemResource.of(ItemStack(Items.GOLD_INGOT))
		val emerald = ItemResource.of(ItemStack(Items.EMERALD))
		val iron = ItemResource.of(ItemStack(Items.IRON_INGOT))

		val diamondPattern = pattern(ItemStack(Items.DIAMOND), ItemStack(Items.GOLD_INGOT), ItemStack(Items.EMERALD))
		val goldPattern = pattern(ItemStack(Items.GOLD_INGOT), ItemStack(Items.IRON_INGOT), ItemStack(Items.IRON_INGOT))
		val emeraldPattern = pattern(ItemStack(Items.EMERALD), ItemStack(Items.IRON_INGOT))

		val result = CraftingResolver.resolve(
			target = diamond,
			amount = 1,
			stockOf = { if (it == iron) 3 else 0 },
			patternFor = {
				when (it) {
					diamond -> diamondPattern
					gold -> goldPattern
					emerald -> emeraldPattern
					else -> null
				}
			},
		)

		assertTrue(result is CraftingResolver.Result.Success) { "Expected a resolvable plan, got $result" }
		val plan = (result as CraftingResolver.Result.Success).plan
		// Gold needs 2 iron, emerald needs 1 - if iron's demand were double-counted per branch
		// instead of summed once, this would come out as something other than exactly 3.
		assertTrue(plan.stockPulls[iron] == 3L) { "Expected iron demand to be deduped/summed to exactly 3 (2 for gold + 1 for emerald), got ${plan.stockPulls}" }
		assertTrue(plan.steps.size == 3) { "Expected exactly 3 craft steps (gold, emerald, diamond), got ${plan.steps}" }

		val diamondIndex = plan.steps.indexOfFirst { it.pattern == diamondPattern }
		val goldIndex = plan.steps.indexOfFirst { it.pattern == goldPattern }
		val emeraldIndex = plan.steps.indexOfFirst { it.pattern == emeraldPattern }
		assertTrue(goldIndex in 0 until diamondIndex && emeraldIndex in 0 until diamondIndex) {
			"Expected both sub-crafts to precede the diamond craft in bottom-up order, got ${plan.steps}"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testCyclicPatternChainIsRejected() {
		val a = ItemResource.of(ItemStack(Items.DIAMOND))
		val b = ItemResource.of(ItemStack(Items.EMERALD))
		val patternA = pattern(ItemStack(Items.DIAMOND), ItemStack(Items.EMERALD))
		val patternB = pattern(ItemStack(Items.EMERALD), ItemStack(Items.DIAMOND))

		val result = CraftingResolver.resolve(
			target = a,
			amount = 1,
			stockOf = { 0 },
			patternFor = { if (it == a) patternA else if (it == b) patternB else null },
		)

		assertTrue(result is CraftingResolver.Result.Cyclic) { "Expected a self-referential pattern chain to be rejected as cyclic, got $result" }
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testMissingIngredientWithNoStockOrPatternIsUnresolvable() {
		val target = ItemResource.of(ItemStack(Items.DIAMOND))
		val missing = ItemResource.of(ItemStack(Items.EMERALD))
		val recipe = pattern(ItemStack(Items.DIAMOND), ItemStack(Items.EMERALD))

		val result = CraftingResolver.resolve(
			target = target,
			amount = 1,
			stockOf = { 0 },
			patternFor = { if (it == target) recipe else null },
		)

		assertTrue(result == CraftingResolver.Result.Unresolvable(missing)) {
			"Expected the missing, unpatterned, out-of-stock ingredient to be reported unresolvable, got $result"
		}
		succeed()
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testMaxCraftableFindsTheLargestResolvableAmount() {
		val diamond = ItemResource.of(ItemStack(Items.DIAMOND))
		val emerald = ItemResource.of(ItemStack(Items.EMERALD))
		val recipe = pattern(ItemStack(Items.DIAMOND), ItemStack(Items.EMERALD), ItemStack(Items.EMERALD))
		val stockOf = { resource: ItemResource -> if (resource == emerald) 5L else 0L }
		val patternFor = { resource: ItemResource -> if (resource == diamond) recipe else null }

		// 5 emeralds, 2 per diamond - 3 diamonds would need 6, one more than the emerald stock has.
		val max = CraftingResolver.maxCraftable(diamond, upperBound = 100, stockOf = stockOf, patternFor = patternFor)
		assertTrue(max == 2L) { "Expected exactly 2 diamonds to be craftable from 5 emeralds at 2 each, got $max" }

		val capped = CraftingResolver.maxCraftable(diamond, upperBound = 1, stockOf = stockOf, patternFor = patternFor)
		assertTrue(capped == 1L) { "Expected maxCraftable to never exceed its own upperBound even when more is resolvable, got $capped" }

		val none = CraftingResolver.maxCraftable(ItemResource.of(ItemStack(Items.NETHER_STAR)), upperBound = 10, stockOf = { 0 }, patternFor = { null })
		assertTrue(none == 0L) { "Expected an entirely unresolvable resource to report 0 craftable, got $none" }
		succeed()
	}
}
