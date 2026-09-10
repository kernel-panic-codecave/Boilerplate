package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.boilerplate.crafting.gui.CraftingBufferMenu
import net.minecraft.client.Minecraft
import net.kernelpanicsoft.boilerplate.resource.SResourceComponent

/** One [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferJob] currently running on the cluster a [CraftingBufferMenu] is open on - [tree] is its own [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferJob.toTree], `null` for a steps-empty (pure stock) job. */
@Serializable
data class CraftingBufferActiveJobView(
	val id: String,
	val resource: SResourceComponent,
	val targetAmount: Long,
	val delivered: Long,
	val status: String,
	val tree: CraftJobTreeNode?,
)

/** One still-queued [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferJob] on a cluster's own backlog, waiting its turn - see [CraftingBufferStatusPacket.backlog]. */
@Serializable
data class CraftingBufferBacklogEntryView(val id: String, val resource: SResourceComponent, val amount: Long)

/** Server -> client: reply to [RequestCraftingBufferStatusPacket] - the requesting player's currently open [CraftingBufferMenu]'s own cluster's active job (if any) and backlog, in order. */
@Serializable
data class CraftingBufferStatusPacket(val active: CraftingBufferActiveJobView?, val backlog: List<CraftingBufferBacklogEntryView>) {
	fun handleOnClient() {
		val menu = Minecraft.getInstance().player?.containerMenu as? CraftingBufferMenu ?: return
		menu.applyStatus(active, backlog)
	}
}
