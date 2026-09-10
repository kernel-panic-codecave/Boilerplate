package net.kernelpanicsoft.boilerplate.gametest

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gametest.assertTrue
import net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.StockingRow
import net.kernelpanicsoft.boilerplate.pipe.hook.UNBOUNDED_STOCK
import net.kernelpanicsoft.boilerplate.pipe.hook.configuredFilterOn
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardState
import net.kernelpanicsoft.boilerplate.pipe.hook.filter.ResourceConditionState
import net.kernelpanicsoft.boilerplate.pipe.hook.wantedAmount
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.Fluids

/**
 * The [StockingRow] shared by the requester and the interface: what each entry means, and how much
 * of it is wanted.
 *
 * The case worth the most attention is the last one. A filter card in a cell means "everything I
 * accept" *only once it has been configured*; before that it is an ordinary item and has to stay
 * stockable as one, or a row could never be used to move filter cards around. That distinction is
 * the entire reason [FilterCardState.configured] exists, and nothing else in the codebase would
 * catch it silently flipping.
 */
@Suppress("unused")
class StockingRowGameTest {

	private fun row(): StockingRow = RequesterHookType.createState() as RequesterHookState

	/** A filter card naming [items], marked configured the way the card editor marks one. */
	private fun configuredCard(vararg items: net.minecraft.world.item.Item): ItemResource {
		val stack = ItemStack(ItemRegistry.ResourceFilterCard)
		FilterCardState(stack).apply {
			(currentState() as ResourceConditionState).let { state ->
				items.forEachIndexed { index, item -> state.resourceMatches[index] = ItemResource.of(ItemStack(item)) }
			}
			touchCurrentState()
			configured = true
		}
		return ItemResource.of(stack)
	}

	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testANamedEntryIsWantedAtItsOwnAmount() {
		val row = row()
		row.target(ItemResource.of(ItemStack(Items.DIAMOND)), 12)

		assertTrue(row.wantedAmount(ItemResource.of(ItemStack(Items.DIAMOND))) == 12L) {
			"Expected the row to want 12 diamonds"
		}
		assertTrue(row.wantedAmount(ItemResource.of(ItemStack(Items.EMERALD))) == 0L) {
			"Expected the row to want nothing it does not name"
		}
		succeed()
	}

	/** [UNBOUNDED_STOCK] is reported as itself rather than collapsing to a number - callers branch on it to mean "keep going" rather than "reach this". */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testAnUnboundedEntryReportsUnbounded() {
		val row = row()
		row.target(ItemResource.of(ItemStack(Items.DIAMOND)), UNBOUNDED_STOCK)

		assertTrue(row.wantedAmount(ItemResource.of(ItemStack(Items.DIAMOND))) == UNBOUNDED_STOCK) {
			"Expected an unbounded entry to stay unbounded, got ${row.wantedAmount(ItemResource.of(ItemStack(Items.DIAMOND)))}"
		}
		succeed()
	}

	/** Any registered kind, not just items - the row is the same system a fluid target uses. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testAFluidEntryIsWantedLikeAnyOther() {
		val row = row()
		row.target(FluidResource.of(Fluids.WATER), 4000)

		// A separately built handle, since FluidResource has no equals of its own.
		assertTrue(row.wantedAmount(FluidResource.of(Fluids.WATER)) == 4000L) {
			"Expected a fluid target to be matched by value, got ${row.wantedAmount(FluidResource.of(Fluids.WATER))}"
		}
		assertTrue(row.wantedAmount(FluidResource.of(Fluids.LAVA)) == 0L) {
			"Expected a different fluid to be unwanted"
		}
		succeed()
	}

	/** A configured card stands for everything it accepts, at that cell's own count. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testAConfiguredCardEntryMatchesTheClassItNames() {
		val row = row()
		row.target(configuredCard(Items.DIAMOND, Items.EMERALD), 7)

		assertTrue(row.wantedAmount(ItemResource.of(ItemStack(Items.DIAMOND))) == 7L) {
			"Expected the card entry to want 7 of a resource it accepts"
		}
		assertTrue(row.wantedAmount(ItemResource.of(ItemStack(Items.EMERALD))) == 7L) {
			"Expected the same count to apply to every resource the card accepts"
		}
		assertTrue(row.wantedAmount(ItemResource.of(ItemStack(Items.GOLD_INGOT))) == 0L) {
			"Expected a resource the card rejects to be unwanted"
		}
		succeed()
	}

	/**
	 * A card nobody has configured is **just an item**.
	 *
	 * This is the distinction the `configured` flag exists for. Read the other way, a row holding a
	 * blank card would match everything - and there would be no way to ask a requester to keep a
	 * supply of filter cards somewhere, because the moment you put one in the row it would stop
	 * meaning itself.
	 */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testABlankCardEntryIsStockedAsAnOrdinaryItem() {
		val blank = ItemResource.of(ItemStack(ItemRegistry.ResourceFilterCard))
		val row = row()
		row.target(blank, 3)

		assertTrue(configuredFilterOn(blank) == null) {
			"Expected an unconfigured card not to read as a filter at all"
		}
		assertTrue(row.wantedAmount(blank) == 3L) {
			"Expected a blank card to be wanted as the item it is, got ${row.wantedAmount(blank)}"
		}
		assertTrue(row.wantedAmount(ItemResource.of(ItemStack(Items.DIAMOND))) == 0L) {
			"Expected a blank card entry NOT to match everything - that is what the configured flag prevents"
		}
		succeed()
	}

	/** Earlier columns win, so a specific entry placed before a filter overrides it for that one resource. */
	@GameTest(template = SMALL, timeoutTicks = 5)
	fun GameTestHelper.testAnEarlierEntryOverridesALaterFilter() {
		val row = row()
		row.target(ItemResource.of(ItemStack(Items.DIAMOND)), 2, index = 0)
		row.target(configuredCard(Items.DIAMOND, Items.EMERALD), UNBOUNDED_STOCK, index = 1)

		assertTrue(row.wantedAmount(ItemResource.of(ItemStack(Items.DIAMOND))) == 2L) {
			"Expected the specific diamond entry to win over the later card that also accepts it"
		}
		assertTrue(row.wantedAmount(ItemResource.of(ItemStack(Items.EMERALD))) == UNBOUNDED_STOCK) {
			"Expected the card to still govern everything the earlier entry does not name"
		}
		succeed()
	}
}
