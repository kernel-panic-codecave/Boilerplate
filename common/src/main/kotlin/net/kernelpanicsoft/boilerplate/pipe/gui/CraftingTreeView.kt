package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import earth.terrarium.common_storage_lib.resources.ResourceStack
import net.kernelpanicsoft.archie.gui.composables.basic.ProgressBar
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.*
import net.kernelpanicsoft.archie.gui.layout.*
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.archie.gui.theme.LocalTheme
import net.kernelpanicsoft.archie.gui.theme.ThemeVariants
import net.kernelpanicsoft.archie.gui.util.KColor
import net.kernelpanicsoft.boilerplate.network.CraftJobTreeNode
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
 * [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferJob]'s own tree ([CraftJobTreeNode], built
 * server-side by [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferJob.toTree]) - the "kind of
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
 * Laid out root-first ([RootAlignment.START]): the job's own target leads, with the steps it depends
 * on extending rightward and each arrowhead landing back on the consumer that needs it. That reads
 * as a dependency hierarchy - "this needs these" - which is what the tree actually is, and matches
 * the advancements menu this was modelled on.
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
		val consumers = consumerIds(roots)
		val owners = structuralOwners(roots, consumers)
		val declared = mutableSetOf<Any>()
		tree(::CraftJobNodeBuilder) {
			for ((index, root) in roots.withIndex()) {
				val key: Any = "root-$index"
				declared += key
				node(key) {
					payload = root
					wireChildren(root, key, consumers, owners, declared)
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
 * Every node id that consumes each resource anywhere in [roots] - a root's own id is its
 * `"root-<index>"` key rather than its resource, matching how [CraftingTreeView] declares them.
 *
 * Collected up front because a shared resource's column depends on *all* of its consumers, not just
 * whichever one happened to reach it first: [NodeTreeView] lays each node one column past its
 * deepest parent, so every consumer edge has to exist before that can come out right.
 */
private fun consumerIds(roots: List<CraftJobTreeNode>): Map<Any, Set<Any>> {
	val result = mutableMapOf<Any, MutableSet<Any>>()
	fun walk(node: CraftJobTreeNode, id: Any) {
		for (child in node.children) {
			result.getOrPut(child.resource) { mutableSetOf() } += id
			walk(child, child.resource)
		}
	}
	for ((index, root) in roots.withIndex()) walk(root, "root-$index")
	return result
}

/**
 * Which single consumer each resource should nest *structurally* under - the deepest one, since
 * [NodeTreeView] places a node one column past its deepest parent anyway.
 *
 * [assignRows][NodeTreeView] lays rows out over the owning parent/child relationships only, so the
 * choice is what decides whether the picture looks tidy. Nesting a shared resource under whichever
 * consumer merely reached it first leaves its *owning* edge spanning columns and strands its
 * siblings on separate rows; nesting it under its deepest consumer makes that edge a short hop
 * between neighbouring columns, and only the remaining (genuinely shallower) consumers need an edge
 * that spans. For logs -> planks -> sticks with sticks + planks -> pickaxe that's the difference
 * between a scattered layout and one straight chain with a single arc over it.
 *
 * Depth here is the longest path from a root, not the first one found - a resource reachable by both
 * a short and a long route belongs at the far end of the long one.
 */
private fun structuralOwners(roots: List<CraftJobTreeNode>, consumers: Map<Any, Set<Any>>): Map<Any, Any> {
	val depth = mutableMapOf<Any, Int>()
	fun walk(node: CraftJobTreeNode, id: Any, at: Int) {
		val known = depth[id]
		if (known != null && known >= at) return
		depth[id] = at
		for (child in node.children) walk(child, child.resource, at + 1)
	}
	for ((index, root) in roots.withIndex()) walk(root, "root-$index", 0)

	return consumers.mapValues { (_, ids) -> ids.maxByOrNull { depth[it] ?: 0 } ?: ids.first() }
}

/**
 * Declares [craftNode]'s own children on the [CraftJobNodeBuilder] currently building it. A child
 * nests as a genuine structural child ([NodeBuilder.children]) only under the consumer [owners]
 * picked for it; every other consumer skips it rather than redeclaring (which would crash on its
 * id), and picks the edge back up from the shared node's own side instead.
 *
 * Those remaining edges are added with [NodeBuilder.dependsOn] *on the shared node, naming the
 * consumer*. The direction matters and is the whole point of [consumers] being precomputed:
 * `dependsOn` adds the named node as a **prerequisite**, i.e. a parent, so calling it the other way
 * round (on the consumer, naming the ingredient) declares the ingredient as the consumer's *parent* -
 * the exact inverse of what this tree means, since here a node's children are the things it's made
 * from.
 *
 * That inversion put a shared ingredient one column too shallow: for logs -> planks -> sticks and
 * sticks + planks -> pickaxe, planks got declared under the pickaxe first, then sticks' own repeat
 * of it registered planks as *sticks'* prerequisite - laying sticks out one past planks, so the
 * sticks appeared to come before the planks they're cut from.
 */
private fun CraftJobNodeBuilder.wireChildren(
	craftNode: CraftJobTreeNode,
	craftNodeId: Any,
	consumers: Map<Any, Set<Any>>,
	owners: Map<Any, Any>,
	declared: MutableSet<Any>,
) {
	val newChildren = craftNode.children.filter { child ->
		(owners[child.resource] ?: craftNodeId) == craftNodeId && declared.add(child.resource)
	}
	children {
		for (child in newChildren) {
			node(child.resource) {
				payload = child
				for (consumer in consumers[child.resource].orEmpty()) {
					if (consumer != craftNodeId) dependsOn(consumer)
				}
				wireChildren(child, child.resource, consumers, owners, declared)
			}
		}
	}
}

/** Whether [node]'s own step hasn't even found a pattern provider to feed yet - [net.kernelpanicsoft.boilerplate.crafting.CraftingBufferJob.stepStatus]'s own "Waiting for a pattern provider" case, the one status [CraftJobTreeNode.progress] can't distinguish from "still feeding" on its own. */
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
