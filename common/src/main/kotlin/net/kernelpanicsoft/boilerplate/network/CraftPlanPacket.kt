package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SBlockPos
import net.kernelpanicsoft.boilerplate.pipe.gui.CraftPlanMenu
import net.minecraft.client.Minecraft
import net.kernelpanicsoft.boilerplate.network.SResourceComponent

/** One line of a [CraftPlanPacket]: [amount] of [resource], either crafted by a planned step or pulled from stock. */
@Serializable
data class CraftPlanEntry(val resource: SResourceComponent, val amount: Long)

/**
 * One Crafting CPU cluster a job could be submitted to.
 *
 * @property pos The cluster's leader position, which is how [net.kernelpanicsoft.boilerplate.crafting.CraftingCpuManager] identifies it.
 * @property backlog How many jobs it already has queued - the tiebreak the automatic pick uses, and
 *   the thing worth knowing when overriding that pick by hand.
 */
@Serializable
data class CraftCpuOption(val pos: SBlockPos, val backlog: Int)

/**
 * Server -> client: the dry run behind a craft request's confirmation step - what crafting [amount]
 * of [resource] would actually entail, and where it could run.
 *
 * Richer than [CraftPreviewPacket], which answers only "how many are craftable". This is what lets
 * the request flow show its work before committing: the steps it would run, what it would take
 * straight from stock, and which Crafting CPU would take the job. Exactly one of a successful plan
 * ([steps]/[stockPulls]) or a failure ([missing] or [cyclic]) is meaningful.
 *
 * @property missing Every resource with neither stock nor a pattern producing it, with how much was
 *   wanted - the same shape as [steps]/[stockPulls] so all three are rows of one table rather than
 *   three separately formatted lists. A list because a plan is routinely blocked on several at once,
 *   and surfacing them one at a time makes the reader fix, retry, and discover the next.
 * @property cyclic Every resource whose pattern chain requires itself.
 * @property cpus Every reachable cluster, in the order [net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment.reachableCraftingCpus]
 *   found them. Empty means there is nowhere to run the job, which blocks the request as surely as
 *   an unresolvable ingredient does.
 */
@Serializable
data class CraftPlanPacket(
	val resource: SResourceComponent,
	val amount: Long,
	val steps: List<CraftPlanEntry>,
	val stockPulls: List<CraftPlanEntry>,
	val missing: List<CraftPlanEntry> = emptyList(),
	val cyclic: List<SResourceComponent> = emptyList(),
	val cpus: List<CraftCpuOption> = emptyList(),
) {
	/** Whether this describes a plan that could actually run. */
	val resolvable: Boolean get() = missing.isEmpty() && cyclic.isEmpty()

	fun handleOnClient() {
		val menu = Minecraft.getInstance().player?.containerMenu as? CraftPlanMenu ?: return
		menu.updateCraftPlan(this)
	}
}
