package net.kernelpanicsoft.tubularstorage.pipe.gui

import net.kernelpanicsoft.tubularstorage.network.CraftJobTreeNode

/**
 * Shared surface for the node-based crafting job tree ([CraftingTreeView]), implemented by every
 * terminal-family menu with its own [net.kernelpanicsoft.tubularstorage.pipe.hook.TerminalHookState.jobs]
 * queue - lets [net.kernelpanicsoft.tubularstorage.network.CraftJobTreePacket] dispatch without
 * knowing which concrete menu class is actually open, the same reason [CraftPreviewMenu] exists
 * for the craft-preview slice of this same surface.
 */
interface CraftTreeMenu {
	/** The most recently received job tree, or `null` if nothing is in progress - Compose state, so [CraftingTreeView] recomposes whenever [updateCraftTree] applies a fresh [net.kernelpanicsoft.tubularstorage.network.CraftJobTreePacket]. */
	val craftTree: CraftJobTreeNode?

	/** Client-side: applies a freshly received [net.kernelpanicsoft.tubularstorage.network.CraftJobTreePacket]. */
	fun updateCraftTree(root: CraftJobTreeNode?)

	/** Client-side: asks the server for this menu's own current job tree. */
	fun requestCraftTree()

	/** Server-side: computes and replies with this menu's own current job tree - see [net.kernelpanicsoft.tubularstorage.crafting.CraftingJob.toTree]. */
	fun sendCraftTree()
}
