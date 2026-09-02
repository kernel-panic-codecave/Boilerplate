package net.kernelpanicsoft.boilerplate.pipe.hook

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import kotlinx.serialization.builtins.serializer
import net.kernelpanicsoft.boilerplate.network.ResourceComponentSerializer
import net.kernelpanicsoft.boilerplate.network.SResourceComponent

/**
 * Self-contained state for one [RequesterHookType] attachment - a [StockingRow] of standing orders:
 * what to keep supplied on the far side of this hook, and how much of each.
 *
 * A row rather than the single stack it used to be, because a stack could only ever express one
 * order, capped at its own stack size, and only by the player surrendering real items to hold it.
 * Entries are ghosts now: any amount, [UNBOUNDED_STOCK] for "just keep exporting", and a configured
 * filter card in a cell to mean a whole class of resources at once.
 */
class RequesterHookState : HookHolderState(RequesterHookType.ID), StockingRow {
	override val targets: MutableList<SResourceComponent> by listField(ResourceComponentSerializer) { List(SLOTS) { ItemResource.BLANK } }

	override val targetAmounts: MutableList<Long> by listField(Long.serializer()) { List(SLOTS) { 1L } }

	var ticksSinceRequest: Int = 0

	companion object {
		/** Matches [InterfaceHookState.SLOTS] - the two rows are the same system and read as siblings. */
		const val SLOTS = 9
	}
}
