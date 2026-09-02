package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import net.kernelpanicsoft.archie.serialization.NBTHolder
import net.kernelpanicsoft.archie.serialization.NestedNBTHolder
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterMode
import net.kernelpanicsoft.boilerplate.pipe.entity.FilterModeSerializer
import net.kernelpanicsoft.boilerplate.registry.FilterConditionTypeRegistry
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

/**
 * Reads/writes one [FilterCardItem] stack's own config, persisted the same way
 * [net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity] persists its [hooks] - [type]
 * is fixed by which [FilterCardItem] this stack actually is (see its own KDoc for why, mirroring
 * [net.kernelpanicsoft.boilerplate.pipe.item.HookItem.hookId]), never stored itself; [mode]
 * lives at the root, and the registered [FilterConditionType]'s own [FilterConditionState] is kept
 * in [conditionStates] under [type]'s own id. Unlike
 * [net.kernelpanicsoft.boilerplate.pipe.gui.FilterCardMenu] (which declares this exact same
 * shape directly on itself, for `@Sync` push while the GUI is open), this is the read path used
 * during ordinary route/request resolution, long after any GUI that configured the card closed.
 */
class FilterCardState(stack: ItemStack) {
	private val holder: NBTHolder = NBTHolder.item(stack)

	/** Which registered [FilterConditionType] this card evaluates - fixed by [stack]'s own item. */
	val type: ResourceLocation = (stack.item as? FilterCardItem)?.conditionTypeId ?: ItemConditionType.ID

	var mode: FilterMode by holder.field(FilterModeSerializer) { FilterMode.WHITELIST }

	/**
	 * Whether a player has actually configured this card, as opposed to it being a fresh one straight
	 * off the crafting grid.
	 *
	 * Load-bearing rather than cosmetic: a card sitting in a
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.StockingRow] entry means "everything I accept" only
	 * once configured. Before that it is an ordinary item, and has to stay stockable *as* one - a row
	 * entry holding a blank card means "keep filter cards here". Without this flag the two are
	 * indistinguishable, since a blank card's condition state is also what an unconfigured one has.
	 *
	 * Set by every edit path in the card editor (see
	 * [net.kernelpanicsoft.boilerplate.pipe.gui.FilterCardMenu]) and cleared by crafting the card on
	 * its own, which is what turns a configured card back into a stockable blank.
	 */
	var configured: Boolean by holder.field(Boolean.serializer()) { false }

	private val conditionStates: NestedNBTHolder<FilterConditionState> by holder.nestedField { tag ->
		// tryParse, not parse - see FilterCardMenu.conditionStates' own note: this factory is called
		// unguarded from NestedNBTHolder.loadFrom, and a card written in the older map shape has no
		// top-level "type" for parse() to accept.
		val id = ResourceLocation.tryParse(tag.getString("type"))
		id?.let { FilterConditionTypeRegistry.byId(it) }?.createState()
	}

	/** [type]'s own [FilterConditionState] - reconstructed fresh (default-valued) the first time this card's data is ever touched. */
	fun currentState(): FilterConditionState? {
		val conditionType = FilterConditionTypeRegistry.byId(type) ?: return null
		return conditionStates.getOrSet { conditionType.createState() }
	}

	/**
	 * Call after mutating [currentState]'s own field(s) in place - [NestedNBTHolder] only
	 * marks itself (and by extension this card's own backing stack) dirty on a *structural* change
	 * ([net.kernelpanicsoft.archie.serialization.NestedNBTHolder.value]), never on an
	 * existing entry's own field write, per its own KDoc. Forgetting this after an edit leaves the
	 * in-memory change fully applied but never actually persisted to the stack.
	 */
	fun touchCurrentState() = conditionStates.touch()

	/** Whether [context] satisfies this card's own condition, [mode] aside - see [accepts] for the mode-applied result. Unrecognized [type] (e.g. an addon's card missing at runtime) never matches. */
	fun matches(context: FilterContext): Boolean {
		val conditionType = FilterConditionTypeRegistry.byId(type) ?: return false
		val state = currentState() ?: return false
		return conditionType.matches(state, context)
	}

	/** [matches], inverted when [mode] is [FilterMode.BLACKLIST] - the same whitelist/blacklist semantic every other filter in this mod already uses. */
	fun accepts(context: FilterContext): Boolean = matches(context) == (mode == FilterMode.WHITELIST)
}
