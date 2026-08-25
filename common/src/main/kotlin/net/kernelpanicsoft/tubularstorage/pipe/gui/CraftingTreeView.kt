package net.kernelpanicsoft.tubularstorage.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import earth.terrarium.common_storage_lib.resources.ResourceStack
import net.kernelpanicsoft.archie.gui.composables.basic.ProgressBar
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.ConnectorAnimation
import net.kernelpanicsoft.archie.gui.composables.containers.ConnectorShape
import net.kernelpanicsoft.archie.gui.composables.containers.ConnectorStyle
import net.kernelpanicsoft.archie.gui.composables.containers.NodeBuilder
import net.kernelpanicsoft.archie.gui.composables.containers.NodeFrame
import net.kernelpanicsoft.archie.gui.composables.containers.NodeTreeView
import net.kernelpanicsoft.archie.gui.composables.containers.Panel
import net.kernelpanicsoft.archie.gui.composables.containers.RootAlignment
import net.kernelpanicsoft.archie.gui.composables.containers.TreeNode
import net.kernelpanicsoft.archie.gui.composables.containers.tree
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Box
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.archie.gui.theme.LocalTheme
import net.kernelpanicsoft.archie.gui.theme.ThemeVariants
import net.kernelpanicsoft.archie.gui.util.KColor
import net.kernelpanicsoft.tubularstorage.network.CraftJobTreeNode
import net.minecraft.network.chat.Component

private const val DONE_LINE_COLOR = 0xFF55CC55.toInt()
private const val STALLED_LINE_COLOR = 0xFFCC5555.toInt()
private const val DEFAULT_LINE_COLOR = 0xFF808080.toInt()

/** [tree]'s own per-node builder for a [CraftJobTreeNode] forest - [payload] is already fully built server-side, so [build] just returns it as-is. */
private class CraftJobNodeBuilder(id: Any) : NodeBuilder<Any, CraftJobTreeNode, CraftJobNodeBuilder>(id) {
	lateinit var payload: CraftJobTreeNode
	override fun build() = payload
}

/**
 * A node-based, click-drag-pannable, scroll-to-zoom *forest* view of every currently in-flight
 * [net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferJob]'s own tree ([CraftJobTreeNode], built
 * server-side by [net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferJob.toTree]) - the "kind of
 * like the advancements menu" tree the terminal family's own Tree tab shows, one column per depth,
 * each node showing its own resource/amount/status - built on Archie's generic [NodeTreeView],
 * every column/row/gap sized from each node's own real measured content rather than a fixed guess.
 * More than one entry in [roots] simply stacks as independent trees sharing the same columns -
 * there's no single canonical "the" job the way a lone `root` parameter would assume, the moment a
 * hook's own queue holds more than one. Empty [roots] (nothing in progress) shows a plain message
 * instead of an empty canvas.
 *
 * [roots] is rebuilt into a [tree] registry (keyed by [CraftJobTreeNode.resource] - except a tree's
 * own root, keyed by its own position in [roots] instead, so two *separate* jobs that happen to
 * target the exact same resource - or even land on identical status text at the exact same tick -
 * still draw as two distinct trees rather than silently collapsing into one) whenever [roots]
 * itself changes. A resource already declared once uses [NodeBuilder.dependsOn] instead of
 * redeclaring it, so a shared sub-resource two different consumers both need collapses into one
 * visual node with multiple incoming connectors - unlike [CraftJobTreeNode]'s own underlying data
 * shape (a plain tree, the same shared resource appearing once per consumer) - clearer at a glance
 * for a job with real sharing (logs feeding both planks and, two steps later, sticks-via-planks,
 * say) than drawing the same step twice, and this applies across different jobs' own trees too.
 *
 * Each connector's own color/style/animation reflects the *child* step's current status: green once
 * [CraftJobTreeNode.done], a gentle flowing shimmer while its own ingredients are still being fed
 * or it's actively processing, a dim, sine-waved [ConnectorStyle.DISCONNECTED] look while it's
 * stalled waiting for a pattern provider (`stepStatus`'s own "Waiting for a pattern provider" case)
 * - reachable at a glance without needing to read every node's own status text individually.
 */
@Composable
fun CraftingTreeView(roots: List<CraftJobTreeNode>, modifier: Modifier = Modifier) {
	if (roots.isEmpty())
	{
		Panel(modifier = modifier, variant = "inset") {
			Box(contentAlignment = Alignment.Center) {
				Text(Component.literal("No crafting job in progress"), dropShadow = false)
			}
		}
		return
	}

	val registry = remember(roots) {
		val declared = mutableSetOf<Any>()
		tree(::CraftJobNodeBuilder) {
			for ((index, root) in roots.withIndex()) {
				val key: Any = "root-$index"
				declared += key
				node(key) {
					payload = root
					wireChildren(root, declared)
				}
			}
		}
	}

	NodeTreeView(
		roots = registry,
		connectorStyle = { node -> if (isStalled(node.data)) ConnectorStyle.DISCONNECTED else ConnectorStyle.ARROW },
		connectorColor = { node -> if (node.data.done) DONE_LINE_COLOR else if (isStalled(node.data)) STALLED_LINE_COLOR else DEFAULT_LINE_COLOR },
		connectorAnimation = { node -> if (node.data.done || isStalled(node.data)) ConnectorAnimation.NONE else ConnectorAnimation.FLOWING },
		connectorShape = { ConnectorShape.SPLINE },
		rootAlignment = RootAlignment.END,
		modifier = modifier,
	) { node ->
		CraftStepNode(node)
	}
}

/**
 * Declares [craftNode]'s own children on the [CraftJobNodeBuilder] currently building it - a child
 * resource seen for the first time anywhere in this [tree] call nests as a genuine structural child
 * ([NodeBuilder.children]); a repeat instead uses [NodeBuilder.dependsOn] to add an extra connector
 * back to the node already declared for it, without redeclaring (and crashing on) its id.
 */
private fun CraftJobNodeBuilder.wireChildren(craftNode: CraftJobTreeNode, declared: MutableSet<Any>) {
	val newChildren = mutableListOf<CraftJobTreeNode>()
	for (child in craftNode.children) {
		if (declared.add(child.resource)) newChildren += child
		else dependsOn(child.resource)
	}
	children {
		for (child in newChildren) {
			node(child.resource) {
				payload = child
				wireChildren(child, declared)
			}
		}
	}
}

/** Whether [node]'s own step hasn't even found a pattern provider to feed yet - [net.kernelpanicsoft.tubularstorage.crafting.CraftingBufferJob.stepStatus]'s own "Waiting for a pattern provider" case, the one status [CraftJobTreeNode.progress] can't distinguish from "still feeding" on its own. */
private fun isStalled(node: CraftJobTreeNode): Boolean = !node.done && node.status.startsWith("Waiting for a pattern provider")

@Composable
private fun CraftStepNode(node: TreeNode<Any, CraftJobTreeNode>, modifier: Modifier = Modifier) {
	NodeFrame(modifier = modifier, variant = if (node.parents.isEmpty()) "challenge" else ThemeVariants.DEFAULT) {
		Column(verticalArrangement = Arrangement.spacedBy(2)) {
			Row(horizontalArrangement = Arrangement.spacedBy(4), verticalAlignment = Alignment.CenterVertically) {
				FakeSlot(ResourceStack(node.data.resource, node.data.amount), isHovered = false)
				Column {
					Text(node.data.resource.cachedStack.hoverName, dropShadow = false)
					Text(
						Component.literal(node.data.status),
						dropShadow = false,
						color = if (node.data.done) KColor.GREEN else LocalTheme.current.darkTextColor,
					)
				}
			}
			ProgressBar(progress = node.data.progress, modifier = Modifier.width(90))
		}
	}
}
