package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import androidx.compose.runtime.Composable
import net.kernelpanicsoft.boilerplate.network.UpdateFilterCardFieldPacket

/**
 * A kind of condition a [FilterCardItem][net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardItem]
 * can evaluate - a real registry entry (see
 * [net.kernelpanicsoft.boilerplate.registry.FilterConditionTypeRegistry]/[Registrars]) rather
 * than a hardcoded enum, mirroring [net.kernelpanicsoft.boilerplate.pipe.hook.PipeHookType]'s
 * own reasoning and shape exactly: a future condition kind (NBT, enchantment, durability - from
 * this mod or an addon) is just another registered `(FilterConditionType, FilterConditionState,
 * [Content])` triple, with only the fields (and editor UI) it actually needs, not an edit to a
 * closed set every existing card's data - or [net.kernelpanicsoft.boilerplate.pipe.gui.FilterCardScreen]'s
 * own rendering code, or [UpdateFilterCardFieldPacket]'s own handler - would need to migrate
 * around.
 */
abstract class FilterConditionType<S : FilterConditionState> {
	/** Builds a fresh, default-valued state for a new card of this condition kind. */
	abstract fun createState(): S

	/** Whether [state]'s own fields match [context] - [FilterCardState.mode] is applied by the caller ([FilterCardState.accepts]), not here. */
	abstract fun matches(state: S, context: FilterContext): Boolean

	/**
	 * Renders this condition kind's own editable field(s) inside the card editor - [state] is the
	 * live instance from [editor]'s own [FilterCardEditor.state] (not a copy). Mutate its fields
	 * directly for the optimistic local read, and push the change with [FilterCardEditor.push] - no
	 * further plumbing needed, since [UpdateFilterCardFieldPacket] dispatches generically by field
	 * name.
	 *
	 * [editor] rather than a menu, because the editor is a layer over whatever screen you opened it
	 * from - see [FilterCardEditor] for what that buys.
	 */
	@Composable
	abstract fun content(editor: FilterCardEditor, state: S)
}

/** How wide the card editor's own content column is - shared so a condition kind's fields line up with the mode selector above them. */
const val FILTER_CARD_CONTENT_WIDTH = 18 * 9
