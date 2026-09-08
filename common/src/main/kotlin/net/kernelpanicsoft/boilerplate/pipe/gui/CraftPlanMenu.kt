package net.kernelpanicsoft.boilerplate.pipe.gui

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.boilerplate.network.CraftPlanPacket
import net.minecraft.core.BlockPos
import earth.terrarium.common_storage_lib.resources.ResourceComponent

/**
 * The surface [craftRequestWizard] needs from whichever terminal-flavored menu opened it: a dry run
 * of a craft request, and the ability to submit it to a chosen Crafting CPU.
 *
 * Separate from [CraftPreviewMenu] rather than folded into it because the two answer different
 * questions at different costs - "how many could I make" is cheap enough to re-ask on every
 * keystroke, where a full plan is worth asking for only once the amount has settled.
 */
interface CraftPlanMenu {
	/** The most recently received plan, or `null` before one has been requested or while one is in flight. */
	val craftPlan: CraftPlanPacket?

	/** Client-side: asks what crafting [amount] of [resource] would entail. Clears [craftPlan] until the reply lands. */
	fun requestCraftPlan(resource: ResourceComponent, amount: Long)

	/** Client-side: applies a freshly received [CraftPlanPacket]. */
	fun updateCraftPlan(plan: CraftPlanPacket)

	/** Server-side: resolves and replies with the plan for [amount] of [resource]. */
	fun sendCraftPlan(resource: ResourceComponent, amount: Long)

	/** Client-side: submits the request, optionally pinning it to the CPU cluster led by [cpu] rather than letting the server pick. */
	fun requestCraft(resource: ResourceComponent, amount: Long, cpu: BlockPos?)
}
