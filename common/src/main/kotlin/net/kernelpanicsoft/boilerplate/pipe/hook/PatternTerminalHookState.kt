package net.kernelpanicsoft.boilerplate.pipe.hook

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.boilerplate.crafting.PatternKind
import net.kernelpanicsoft.boilerplate.crafting.PatternKindSerializer
import net.kernelpanicsoft.boilerplate.network.ItemResourceSerializer
import net.kernelpanicsoft.boilerplate.network.SItemResource

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

	val ghostInputs: MutableList<SItemResource> by listField(ItemResourceSerializer) { List(GRID_SIZE) { ItemResource.BLANK } }

	val ghostOutputs: MutableList<SItemResource> by listField(ItemResourceSerializer) { List(GRID_SIZE) { ItemResource.BLANK } }
	val ghostOutputAmounts: MutableList<Long> by listField(Long.serializer()) { List(GRID_SIZE) { 1L } }

	val blankPatterns: ArchieItemStorage by itemField(1)
	val patternOutput: ArchieItemStorage by itemField(1)

	companion object {
		const val GRID_SIZE = 9
	}
}
