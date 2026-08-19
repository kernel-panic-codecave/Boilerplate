package net.kernelpanicsoft.tubularstorage.pipe.hook.filter

import androidx.compose.runtime.Composable
import kotlinx.serialization.KSerializer
import net.kernelpanicsoft.archie.config.toSnakeCase
import net.kernelpanicsoft.archie.gui.blockentity.toSerializedValue
import net.kernelpanicsoft.tubularstorage.network.TubularStorageNetworkChannel
import net.kernelpanicsoft.tubularstorage.network.UpdateFilterCardFieldPacket
import net.kernelpanicsoft.tubularstorage.pipe.gui.FilterCardMenu
import net.kernelpanicsoft.tubularstorage.pipe.gui.MiddleClickHandler
import kotlin.reflect.KProperty0

/**
 * A kind of condition a [FilterCardItem][net.kernelpanicsoft.tubularstorage.pipe.hook.filter.FilterCardItem]
 * can evaluate - a real registry entry (see
 * [net.kernelpanicsoft.tubularstorage.registry.FilterConditionTypeRegistry]/[Registrars]) rather
 * than a hardcoded enum, mirroring [net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType]'s
 * own reasoning and shape exactly: a future condition kind (NBT, enchantment, durability - from
 * this mod or an addon) is just another registered `(FilterConditionType, FilterConditionState,
 * [Content])` triple, with only the fields (and editor UI) it actually needs, not an edit to a
 * closed set every existing card's data - or [net.kernelpanicsoft.tubularstorage.pipe.gui.FilterCardScreen]'s
 * own rendering code, or [UpdateFilterCardFieldPacket]'s own handler - would need to migrate
 * around.
 */
abstract class FilterConditionType<S : FilterConditionState> {
	/** Builds a fresh, default-valued state for a new card of this condition kind. */
	abstract fun createState(): S

	/** Whether [state]'s own fields match [context] - [FilterCardState.mode] is applied by the caller ([FilterCardState.accepts]), not here. */
	abstract fun matches(state: S, context: FilterContext): Boolean

	/**
	 * Renders this condition kind's own editable field(s) inside
	 * [net.kernelpanicsoft.tubularstorage.pipe.gui.FilterCardScreen] - [state] is the live instance
	 * from [menu]'s own [FilterCardMenu.currentConditionState] (not a copy). Mutate its fields
	 * directly for the optimistic local read, and push the change with [pushFieldUpdate] - no
	 * further plumbing back through this screen needed, since [UpdateFilterCardFieldPacket]
	 * dispatches generically by field name.
	 */
	@Composable
	abstract fun Content(menu: FilterCardMenu, state: S, middleClickHandler: MiddleClickHandler)
}

/**
 * Pushes an edit to [property] (one declared via [FilterConditionState.editableField]/
 * [FilterConditionState.editableListField]) to the server, for whichever [FilterCardMenu] the
 * current screen has open - see [UpdateFilterCardFieldPacket]'s own KDoc. [property]'s own runtime
 * name (not a hand-typed string) becomes the wire key, so a rename can't silently desync from
 * whatever [FilterConditionState.applyFieldUpdate] looks up.
 */
fun <T> pushFieldUpdate(property: KProperty0<T>, value: T, serializer: KSerializer<T>) {
	TubularStorageNetworkChannel.toServer(UpdateFilterCardFieldPacket(property.name.toSnakeCase(), value.toSerializedValue(serializer)))
}

/** [net.kernelpanicsoft.tubularstorage.pipe.gui.FilterCardScreen]'s own content width, shared with every [FilterConditionType.Content] override so a text field/ghost grid lines up with the rest of the screen. */
const val FILTER_CARD_CONTENT_WIDTH = 18 * 9
