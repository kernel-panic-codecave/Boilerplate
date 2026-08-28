package net.kernelpanicsoft.boilerplate.pipe.hook.filter

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.serialization.NBTHolder
import net.kernelpanicsoft.archie.serialization.NestedNBTHolderMap
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

	private val conditionStates: NestedNBTHolderMap<FilterConditionState> by holder.nestedMapField { tag ->
		val id = ResourceLocation.parse(tag.getString("type"))
		FilterConditionTypeRegistry.byId(id)?.createState() ?: error("Unknown filter condition type $id")
	}

	/** [type]'s own [FilterConditionState] - reconstructed fresh (default-valued) the first time this card's data is ever touched. */
	fun currentState(): FilterConditionState? {
		val conditionType = FilterConditionTypeRegistry.byId(type) ?: return null
		return conditionStates.getOrPut(type.toString()) { conditionType.createState() }
	}

	/**
	 * Call after mutating [currentState]'s own field(s) in place - [NestedNBTHolderMap] only
	 * marks itself (and by extension this card's own backing stack) dirty on a *structural* change
	 * ([net.kernelpanicsoft.archie.serialization.NestedNBTHolderMap.getOrPut]/`remove`), never on an
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

/**
 * Whether [context] matches one ghost grid slot's [resource] - a blank slot never matches, a
 * [FilterCardItem]-identified one delegates to its own [FilterCardState.accepts] (recursively, for
 * a [CombinedConditionType] card's own children), and any other (plain) item falls back to a bare
 * identity match - the simple behavior every filter grid had before filter cards existed. Shared
 * by [net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState]'s outer grid and
 * [CombinedConditionState.children] alike, so nesting a filter card inside a filter card behaves
 * exactly like dropping one into a hook's own filter grid.
 */
fun evaluateGhostSlot(resource: ItemResource, context: FilterContext): Boolean {
	if (resource.isBlank) return false
	return if (resource.item is FilterCardItem) {
		FilterCardState(resource.toStack(1)).accepts(context)
	} else {
		resource.isOf(context.resource.item)
	}
}
