package net.kernelpanicsoft.tubularstorage.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import earth.terrarium.common_storage_lib.resources.ResourceStack
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.Panel
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Box
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.MeasurePolicy
import net.kernelpanicsoft.archie.gui.layout.MeasureResult
import net.kernelpanicsoft.archie.gui.layout.Layout
import net.kernelpanicsoft.archie.gui.layout.Renderer
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.position.offset
import net.kernelpanicsoft.archie.gui.modifiers.size
import net.kernelpanicsoft.archie.gui.nodes.UINode
import net.kernelpanicsoft.archie.gui.theme.LocalTheme
import net.kernelpanicsoft.archie.gui.util.KColor
import net.kernelpanicsoft.tubularstorage.network.CraftJobTreeNode
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

private const val NODE_WIDTH = 108
private const val NODE_HEIGHT = 32
private const val COLUMN_GAP = 26
private const val ROW_GAP = 6
private const val LINE_THICKNESS = 1
private const val LINE_COLOR = 0xFF808080.toInt()

private data class PositionedNode(val node: CraftJobTreeNode, val x: Int, val y: Int, val parentX: Int, val parentY: Int, val hasParent: Boolean)

/** Flattens [root] into an absolute-positioned node list - column = depth, row = pre-order visit index - see [CraftingTreeView]'s own KDoc for why this simpler layout stands in for a true compacted graph. */
private fun layout(root: CraftJobTreeNode): List<PositionedNode> {
	val result = mutableListOf<PositionedNode>()
	var rowCounter = 0

	fun visit(node: CraftJobTreeNode, depth: Int, parentX: Int, parentY: Int, hasParent: Boolean) {
		val row = rowCounter++
		val x = depth * (NODE_WIDTH + COLUMN_GAP)
		val y = row * (NODE_HEIGHT + ROW_GAP)
		result += PositionedNode(node, x, y, parentX, parentY, hasParent)
		for (child in node.children) visit(child, depth + 1, x, y, true)
	}

	visit(root, 0, 0, 0, false)
	return result
}

/**
 * A node-based, click-drag-pannable view of a [net.kernelpanicsoft.tubularstorage.crafting.CraftingJob]'s
 * tree ([CraftJobTreeNode], built server-side by [net.kernelpanicsoft.tubularstorage.crafting.CraftingJob.toTree])
 * - the "kind of like the advancements menu" tree the terminal family's own Tree tab shows,
 * one column per depth, each node showing its own resource/amount/status. Simpler than a true
 * compacted DAG layout (a shared sub-resource needed by more than one consumer is drawn once per
 * consumer, not deduplicated into a single shared node/edge - see [CraftJobTreeNode]'s own KDoc)
 * and laid out top-to-bottom in plain pre-order rather than minimizing row usage/edge crossings -
 * both deliberate simplifications given how small a crafting job's own tree realistically gets.
 * `null` [root] (nothing in progress) shows a plain message instead of an empty canvas.
 */
@Composable
fun CraftingTreeView(root: CraftJobTreeNode?, modifier: Modifier = Modifier) {
	if (root == null) {
		Box(modifier = modifier, contentAlignment = Alignment.Center) {
			Text(Component.literal("No crafting job in progress"), dropShadow = false)
		}
		return
	}

	val positioned = remember(root) { layout(root) }
	val canvasWidth = (positioned.maxOfOrNull { it.x } ?: 0) + NODE_WIDTH
	val canvasHeight = (positioned.maxOfOrNull { it.y } ?: 0) + NODE_HEIGHT

	PannableCanvas(modifier = modifier) {
		Box(modifier = Modifier.size(canvasWidth, canvasHeight)) {
			TreeConnectors(positioned, canvasWidth, canvasHeight)
			for (positionedNode in positioned) {
				TreeNode(positionedNode.node, modifier = Modifier.offset(positionedNode.x, positionedNode.y))
			}
		}
	}
}

/** Draws every parent-child elbow connector in one pass, relative to whatever absolute position the panned canvas placed this layer at. */
@Composable
private fun TreeConnectors(positioned: List<PositionedNode>, width: Int, height: Int) {
	val measurePolicy = remember(width, height) {
		MeasurePolicy { _, _, _ -> MeasureResult(width, height) {} }
	}
	Layout(
		name = "TreeConnectors",
		measurePolicy = measurePolicy,
		renderer = object : Renderer {
			override fun render(node: UINode, x: Int, y: Int, guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
				for (positionedNode in positioned) {
					if (!positionedNode.hasParent) continue
					val fromX = x + positionedNode.parentX + NODE_WIDTH
					val fromY = y + positionedNode.parentY + NODE_HEIGHT / 2
					val toX = x + positionedNode.x
					val toY = y + positionedNode.y + NODE_HEIGHT / 2
					val midX = fromX + COLUMN_GAP / 2

					guiGraphics.fill(fromX, fromY, midX, fromY + LINE_THICKNESS, LINE_COLOR)
					guiGraphics.fill(midX, minOf(fromY, toY), midX + LINE_THICKNESS, maxOf(fromY, toY) + LINE_THICKNESS, LINE_COLOR)
					guiGraphics.fill(midX, toY, toX, toY + LINE_THICKNESS, LINE_COLOR)
				}
			}
		},
	)
}

@Composable
private fun TreeNode(node: CraftJobTreeNode, modifier: Modifier = Modifier) {
	Panel(modifier = modifier, variant = "inset", contentPadding = 3) {
		Row(horizontalArrangement = Arrangement.spacedBy(4), verticalAlignment = Alignment.CenterVertically) {
			FakeSlot(ResourceStack(node.resource, node.amount), isHovered = false)
			Column {
				Text(node.resource.cachedStack.hoverName, dropShadow = false)
				Text(
					Component.literal(node.status),
					dropShadow = false,
					color = if (node.done) KColor.GREEN else LocalTheme.current.darkTextColor,
				)
			}
		}
	}
}
