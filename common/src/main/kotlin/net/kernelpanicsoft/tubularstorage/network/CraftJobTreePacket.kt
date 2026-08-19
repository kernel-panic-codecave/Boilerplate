package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.tubularstorage.pipe.gui.CraftTreeMenu
import net.minecraft.client.Minecraft

/**
 * One node of a [net.kernelpanicsoft.tubularstorage.crafting.CraftingJob]'s tree, built by
 * [net.kernelpanicsoft.tubularstorage.crafting.CraftingJob.toTree] - [resource]/[amount] is the
 * craft step's own output, [status]/[done] its current progress
 * ([net.kernelpanicsoft.tubularstorage.crafting.CraftingJob.stepStatus]), [children] the steps
 * producing this step's own crafted (not stock-pulled) ingredients. A resource needed by more than
 * one consumer appears once under each of them - a plain tree, not a deduplicated DAG, matching
 * [net.kernelpanicsoft.tubularstorage.pipe.gui.CraftingTreeView]'s own "advancements-style" layout.
 */
@Serializable
data class CraftJobTreeNode(
	val resource: SItemResource,
	val amount: Long,
	val status: String,
	val done: Boolean,
	val children: List<CraftJobTreeNode>,
)

/** Server -> client: reply to [RequestCraftJobTreePacket] - the requesting player's currently open terminal-family menu's own front-of-queue job tree, or `null` if nothing is in progress. */
@Serializable
data class CraftJobTreePacket(val root: CraftJobTreeNode?) {
	fun handleOnClient() {
		val menu = Minecraft.getInstance().player?.containerMenu as? CraftTreeMenu ?: return
		menu.updateCraftTree(root)
	}
}
