package net.kernelpanicsoft.boilerplate.pipe.gui

import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.network.SetStockingTargetPacket
import net.kernelpanicsoft.boilerplate.pipe.hook.StockingRow

/**
 * A menu over a hook that carries a [StockingRow] - the requester and the interface both do, and
 * configure it identically.
 *
 * Exists so one packet ([SetStockingTargetPacket]) and one editor ([StockingRowGrid]) serve both,
 * rather than each hook growing a near-identical pair. A menu implements this by pointing [row] at
 * whichever hook state it is open on.
 */
interface StockingRowMenu {
	/** The row this menu edits, or `null` if the hook it was opened on has since gone. */
	val row: StockingRow?

	/** Server-side: applies a [SetStockingTargetPacket]. */
	fun applyStockingTarget(index: Int, resource: ResourceComponent, amount: Long) {
		val row = row ?: return
		if (index !in row.targets.indices) return
		row.targets[index] = resource
		row.targetAmounts[index] = amount
	}

	/** Client-side: asks the server to apply an edit to column [index]. */
	fun setStockingTarget(index: Int, resource: ResourceComponent, amount: Long) {
		BoilerplateNetworkChannel.toServer(SetStockingTargetPacket(index, resource, amount))
	}

	/** [row]'s current contents, read once when the screen opens - the same "not wired into live sync" reasoning [SortingHookMenu.currentRouting] documents. */
	fun currentStockingTargets(): Pair<List<ResourceComponent>, List<Long>> {
		val row = row ?: return emptyList<ResourceComponent>() to emptyList()
		return row.targets.toList() to row.targetAmounts.toList()
	}
}
