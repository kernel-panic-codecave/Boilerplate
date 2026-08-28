package net.kernelpanicsoft.boilerplate.pipe.gui

import net.kernelpanicsoft.boilerplate.network.CraftJobTreeNode

/**
 * Shared surface for the node-based crafting job tree ([CraftingTreeView]), implemented by every
 * terminal-family menu with its own [net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookState.submittedJobs]
 * list - lets [net.kernelpanicsoft.boilerplate.network.CraftJobTreePacket] dispatch without
 * knowing which concrete menu class is actually open, the same reason [CraftPreviewMenu] exists
 * for the craft-preview slice of this same surface.
 */
interface CraftTreeMenu {
	/** Every currently in-flight job's own tree, empty if nothing is in progress - Compose state, so [CraftingTreeView] recomposes whenever [updateCraftTrees] applies a fresh [net.kernelpanicsoft.boilerplate.network.CraftJobTreePacket]. More than one entry once a hook can run several jobs at once - not one canonical "the" job the way a single-tree view would assume. */
	val craftTrees: List<CraftJobTreeNode>

	/** Client-side: applies a freshly received [net.kernelpanicsoft.boilerplate.network.CraftJobTreePacket]. */
	fun updateCraftTrees(roots: List<CraftJobTreeNode>)

	/** Client-side: asks the server for this menu's own current job trees. */
	fun requestCraftTree()

	/** Server-side: computes and replies with this menu's own current job trees - see [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferJob.toTree]. */
	fun sendCraftTree()
}
