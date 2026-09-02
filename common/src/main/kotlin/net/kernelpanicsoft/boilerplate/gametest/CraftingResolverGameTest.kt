package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.crafting.CraftingResolver
import net.kernelpanicsoft.boilerplate.crafting.Pattern
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.kernelpanicsoft.boilerplate.util.resourceCell
import net.kernelpanicsoft.boilerplate.util.resourceStack
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.boilerplate.network.ResourceIdentity

/**
 * GameTest coverage for [CraftingResolver] - pure-function logic, so these don't touch the world at
 * all (matching [net.kernelpanicsoft.boilerplate.warehouse.WarehouseScale]'s own identical
 * approach for a pure function), just hand-built patterns/stock against the algorithm directly.
 */
@Suppress("unused")
class CraftingResolverGameTest {
	private fun pattern(output: ItemStack, vararg inputs: ItemStack): Pattern = Pattern(
		inputs = inputs.map { it.resourceCell },
		outputs = listOf(output.resourceCell),
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
		assertTrue(plan.stockPulls[ResourceIdentity.of(ironIngot)] == 2L) { "Expected 2 iron ingots pulled from stock, got ${plan.stockPulls}" }
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
		assertTrue(plan.stockPulls[ResourceIdentity.of(iron)] == 3L) { "Expected iron demand to be deduped/summed to exactly 3 (2 for gold + 1 for emerald), got ${plan.stockPulls}" }
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
		val stockOf = { resource: ResourceComponent -> if (resource == emerald) 5L else 0L }
		val patternFor = { resource: ResourceComponent -> if (resource == diamond) recipe else null }

		// 5 emeralds, 2 per diamond - 3 diamonds would need 6, one more than the emerald stock has.
		val max = CraftingResolver.maxCraftable(diamond, upperBound = 100, stockOf = stockOf, patternFor = patternFor)
		assertTrue(max == 2L) { "Expected exactly 2 diamonds to be craftable from 5 emeralds at 2 each, got $max" }

		val capped = CraftingResolver.maxCraftable(diamond, upperBound = 1, stockOf = stockOf, patternFor = patternFor)
		assertTrue(capped == 1L) { "Expected maxCraftable to never exceed its own upperBound even when more is resolvable, got $capped" }

		val none = CraftingResolver.maxCraftable(ItemResource.of(ItemStack(Items.NETHER_STAR)), upperBound = 10, stockOf = { 0 }, patternFor = { null })
		assertTrue(none == 0L) { "Expected an entirely unresolvable resource to report 0 craftable, got $none" }
		succeed()
	}

	/**
	 * Existing stock of the **requested target** never reduces how much gets crafted.
	 *
	 * "Craft me 64 wooden pickaxes" with 32 already on the shelf must plan 64 runs, not 32 runs
	 * plus handing back the 32 you already had - which is what happened while the target went
	 * through the same `stockOf` deduction as its ingredients, and defeats the point of asking for
	 * a craft at all.
	 */
	@GameTest(template = SMALL)
	fun GameTestHelper.testExistingStockOfTheTargetDoesNotReduceTheCraft() {
		val pickaxe = ItemResource.of(ItemStack(Items.WOODEN_PICKAXE))
		val planks = ItemResource.of(ItemStack(Items.OAK_PLANKS))
		val recipe = pattern(ItemStack(Items.WOODEN_PICKAXE), ItemStack(Items.OAK_PLANKS))

		val result = CraftingResolver.resolve(
			target = pickaxe,
			amount = 64,
			// Plenty of the target already in stock, and plenty of its ingredient.
			stockOf = { if (it == pickaxe) 32 else 1024 },
			patternFor = { if (it == pickaxe) recipe else null },
		)

		assertTrue(result is CraftingResolver.Result.Success) { "Expected a resolvable plan, got $result" }
		val plan = (result as CraftingResolver.Result.Success).plan
		assertTrue(plan.steps.size == 1 && plan.steps[0].runs == 64L) {
			"Expected all 64 to be crafted, got ${plan.steps.map { it.runs }}"
		}
		assertTrue(plan.stockPulls[ResourceIdentity.of(pickaxe)] == null) {
			"Expected the target itself never to be pulled from stock, got ${plan.stockPulls}"
		}
		succeed()
	}

	/** The ingredient half of the same rule: stock of anything that *isn't* the target still reduces what has to be crafted. */
	@GameTest(template = SMALL)
	fun GameTestHelper.testExistingStockOfAnIngredientStillReducesSubCrafting() {
		val pickaxe = ItemResource.of(ItemStack(Items.WOODEN_PICKAXE))
		val planks = ItemResource.of(ItemStack(Items.OAK_PLANKS))
		val logs = ItemResource.of(ItemStack(Items.OAK_LOG))

		val pickaxeRecipe = pattern(ItemStack(Items.WOODEN_PICKAXE), ItemStack(Items.OAK_PLANKS))
		val plankRecipe = pattern(ItemStack(Items.OAK_PLANKS), ItemStack(Items.OAK_LOG))

		val result = CraftingResolver.resolve(
			target = pickaxe,
			amount = 4,
			// Enough planks in stock to cover every pickaxe, so no plank sub-craft should be planned.
			stockOf = { if (it == planks) 64 else 0 },
			patternFor = {
				when (it) {
					pickaxe -> pickaxeRecipe
					planks -> plankRecipe
					else -> null
				}
			},
		)

		assertTrue(result is CraftingResolver.Result.Success) { "Expected a resolvable plan, got $result" }
		val plan = (result as CraftingResolver.Result.Success).plan
		assertTrue(plan.stockPulls[ResourceIdentity.of(planks)] == 4L) { "Expected the planks to come from stock, got ${plan.stockPulls}" }
		assertTrue(plan.steps.none { it.resource == planks }) {
			"Expected no plank sub-craft when stock already covers them, got ${plan.steps.map { it.resource }}"
		}
		succeed()
	}

	/**
	 * A batched processing pattern runs once for a whole batch, not once per item.
	 *
	 * `64 sand -> 64 glass` asked for 64 glass is **one** run needing 64 sand - the point of being
	 * able to put a count on an input at all. The same request against a `1 sand -> 1 glass` pattern
	 * is 64 runs, which is what a machine with no batching would have to do.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testABatchedPatternRunsOncePerBatch() {
		val sand = ItemResource.of(ItemStack(Items.SAND))
		val glass = ItemResource.of(ItemStack(Items.GLASS))

		val batched = Pattern(
			inputs = listOf(ResourceStack(sand, 64)),
			outputs = listOf(ResourceStack(glass, 64)),
			kind = PatternKind.PROCESSING,
		)
		val singles = Pattern(
			inputs = listOf(ResourceStack(sand, 1)),
			outputs = listOf(ResourceStack(glass, 1)),
			kind = PatternKind.PROCESSING,
		)

		fun runsFor(recipe: Pattern): Long {
			val result = CraftingResolver.resolve(
				target = glass,
				amount = 64,
				stockOf = { if (it == sand) 1024 else 0 },
				patternFor = { if (it == glass) recipe else null },
			)
			assertTrue(result is CraftingResolver.Result.Success) { "Expected 64 glass to resolve, got $result" }
			return (result as CraftingResolver.Result.Success).plan.steps.single().runs
		}

		assertTrue(runsFor(batched) == 1L) { "Expected a 64-per-run pattern to need exactly one run, got ${runsFor(batched)}" }
		assertTrue(runsFor(singles) == 64L) { "Expected a 1-per-run pattern to need 64 runs, got ${runsFor(singles)}" }
		succeed()
	}
}
