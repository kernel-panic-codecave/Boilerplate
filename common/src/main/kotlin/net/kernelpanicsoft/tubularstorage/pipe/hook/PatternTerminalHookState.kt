package net.kernelpanicsoft.tubularstorage.pipe.hook

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.tubularstorage.network.ItemResourceSerializer
import net.kernelpanicsoft.tubularstorage.network.SItemResource

/**
 * A [TerminalHookState] upgraded with a *ghost* 3x3 grid ([ghostInputs]) plus one ghost output
 * ([ghostOutputResource]/[ghostOutputAmount]) - authors a [net.kernelpanicsoft.tubularstorage.crafting.Pattern]
 * without needing the real items in hand, the same "reference, not real items" grid
 * [SortingHookState.filter] already uses. Inherits [TerminalHookState.jobs]/[TerminalHookState.output]
 * wholesale. See `docs/design/m4-crafting-automation.md`.
 */
class PatternTerminalHookState : TerminalHookState(PatternTerminalHookType.ID) {
	val ghostInputs: MutableList<SItemResource> by listField(ItemResourceSerializer) { List(GRID_SIZE) { ItemResource.BLANK } }

	var ghostOutputResource: SItemResource by field(ItemResourceSerializer) { ItemResource.BLANK }
	var ghostOutputAmount: Long by field(Long.serializer()) { 1L }

	companion object {
		const val GRID_SIZE = 9
	}
}
