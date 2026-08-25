package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.tubularstorage.pipe.gui.CraftTreeMenu
import net.minecraft.client.Minecraft

/**
 * One node of a [net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferJob]'s tree, built by
 * [net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferJob.toTree] - [resource]/[amount] is
 * the craft step's own output, [status]/[done]/[progress] its current progress
 * ([net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferJob.stepStatus] for the text; [progress] is
 * a coarse `0f..1f` reflecting the same states for every node *except* the root, which instead
 * reports the job's own real `delivered / targetAmount` fraction - the one node with genuinely
 * continuous progress data), [children] the steps producing this step's own crafted (not
 * stock-pulled) ingredients. A resource needed by more than one consumer appears once under each of
 * them - a plain tree, not a deduplicated DAG, matching
 * [net.kernelpanicsoft.tubularstorage.pipe.gui.CraftingTreeView]'s own "advancements-style" layout.
 */
@Serializable
data class CraftJobTreeNode(
	val resource: SItemResource,
	val amount: Long,
	val status: String,
	val done: Boolean,
	val progress: Float,
	val children: List<CraftJobTreeNode>,
)

/** Server -> client: reply to [RequestCraftJobTreePacket] - the requesting player's currently open terminal-family menu's own trees, one per in-flight job ([net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferJob.toTree] skips a job whose own target was fully covered straight from stock - nothing to show a tree for), empty if nothing is in progress. */
@Serializable
data class CraftJobTreePacket(val roots: List<CraftJobTreeNode>) {
	fun handleOnClient() {
		val menu = Minecraft.getInstance().player?.containerMenu as? CraftTreeMenu ?: return
		menu.updateCraftTrees(roots)
	}
}
