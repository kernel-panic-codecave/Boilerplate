package net.kernelpanicsoft.boilerplate.pipe.hook

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.kernelpanicsoft.boilerplate.crafting.PatternKindSerializer
import net.kernelpanicsoft.boilerplate.network.ResourceComponentSerializer
import net.kernelpanicsoft.boilerplate.network.SResourceComponent

/**
 * A [TerminalHookState] upgraded with a *ghost* 3x3 input grid ([ghostInputs]) plus, depending on
 * [patternKind], either a live recipe-derived preview (
 * [net.kernelpanicsoft.boilerplate.crafting.PatternKind.CRAFTING] - computed on demand, nothing
 * stored here) or up to 9 manually-specified ghost outputs ([ghostOutputs]/[ghostOutputAmounts],
 * [net.kernelpanicsoft.boilerplate.crafting.PatternKind.PROCESSING]) - authors a
 * [net.kernelpanicsoft.boilerplate.crafting.Pattern] without needing the real items in hand, the
 * same "reference, not real items" grid [SortingHookState.filter] already uses. [blankPatterns] is
 * a real, persistent slot the player stocks with blank
 * [net.kernelpanicsoft.boilerplate.crafting.PatternItem]s for encoding to draw from, rather than
 * reaching into the player's own inventory. Inherits [TerminalHookState.submittedJobs]/
 * [TerminalHookState.output] wholesale. See `docs/design/m4-crafting-automation.md`.
 */
class PatternTerminalHookState : TerminalHookState(PatternTerminalHookType.ID) {
	var patternKind: PatternKind by field(PatternKindSerializer) { PatternKind.CRAFTING }

	/**
	 * The pattern's own input cells. [SResourceComponent], not items: a `PROCESSING` pattern may
	 * name a fluid (or any other registered
	 * [net.kernelpanicsoft.boilerplate.network.ResourceKind]) in a cell, so `1000mB water + 1 clay`
	 * is expressible. A `CRAFTING` pattern is still matched against a real vanilla recipe, which
	 * only knows items - [net.kernelpanicsoft.boilerplate.crafting.PatternEncoder] rejects a
	 * non-item cell in that mode rather than silently dropping it.
	 */
	val ghostInputs: MutableList<SResourceComponent> by listField(ResourceComponentSerializer) { List(GRID_SIZE) { ItemResource.BLANK } }

	/**
	 * How many of each [ghostInputs] entry one run of the pattern consumes - the input mirror of
	 * [ghostOutputAmounts], so `64 sand -> 64 glass` is one pattern that batches at 64 rather than
	 * 64 patterns of one.
	 *
	 * Only meaningful in [net.kernelpanicsoft.boilerplate.crafting.PatternKind.PROCESSING]. A
	 * `CRAFTING` pattern's grid is matched against a real vanilla recipe, which is positional and
	 * one-item-per-cell; encoding a count there would leave
	 * [net.kernelpanicsoft.boilerplate.crafting.Pattern.requiredInputs] demanding that many per run
	 * for a recipe that only ever consumes one, so [net.kernelpanicsoft.boilerplate.pipe.gui.PatternTerminalHookMenu.encode]
	 * ignores this outside `PROCESSING`.
	 */
	val ghostInputAmounts: MutableList<Long> by listField(Long.serializer()) { List(GRID_SIZE) { 1L } }

	/** The pattern's own output cells - same any-kind reasoning as [ghostInputs]. */
	val ghostOutputs: MutableList<SResourceComponent> by listField(ResourceComponentSerializer) { List(GRID_SIZE) { ItemResource.BLANK } }
	val ghostOutputAmounts: MutableList<Long> by listField(Long.serializer()) { List(GRID_SIZE) { 1L } }

	val blankPatterns: ArchieItemStorage by itemField(1)
	val patternOutput: ArchieItemStorage by itemField(1)

	companion object {
		const val GRID_SIZE = 9
	}
}
