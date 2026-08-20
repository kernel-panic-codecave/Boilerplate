package net.kernelpanicsoft.tubularstorage.pipe.gui

import androidx.compose.runtime.Composable
import earth.terrarium.common_storage_lib.resources.ResourceStack
import net.kernelpanicsoft.archie.gui.composables.basic.ProgressBar
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.ConnectorStyle
import net.kernelpanicsoft.archie.gui.composables.containers.NodeTreeView
import net.kernelpanicsoft.archie.gui.composables.containers.Panel
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Box
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.archie.gui.theme.LocalTheme
import net.kernelpanicsoft.archie.gui.util.KColor
import net.kernelpanicsoft.tubularstorage.network.CraftJobTreeNode
import net.minecraft.network.chat.Component

/**
 * A node-based, click-drag-pannable view of a [net.kernelpanicsoft.tubularstorage.crafting.CraftingJob]'s
 * tree ([CraftJobTreeNode], built server-side by [net.kernelpanicsoft.tubularstorage.crafting.CraftingJob.toTree])
 * - the "kind of like the advancements menu" tree the terminal family's own Tree tab shows, one
 * column per depth, each node showing its own resource/amount/status - built on Archie's generic
 * [NodeTreeView]. `null` [root] (nothing in progress) shows a plain message instead of an empty
 * canvas.
 */
@Composable
fun CraftingTreeView(root: CraftJobTreeNode?, modifier: Modifier = Modifier) {
	Panel(modifier = modifier, variant = "inset") {
		if (root == null)
		{
			Box(modifier = modifier, contentAlignment = Alignment.Center) {
				Text(Component.literal("No crafting job in progress"), dropShadow = false)
			}
			return@Panel
		}

		NodeTreeView(root = root, connectorStyle = { ConnectorStyle.ARROW }, children = { it.children }, modifier = modifier) { node ->
			TreeNode(node)
		}
	}
}

@Composable
private fun TreeNode(node: CraftJobTreeNode, modifier: Modifier = Modifier) {
	Panel(modifier = modifier, contentPadding = 3) {
		Column(verticalArrangement = Arrangement.spacedBy(2)) {
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
			ProgressBar(progress = node.progress, modifier = Modifier.width(90))
		}
	}
}
