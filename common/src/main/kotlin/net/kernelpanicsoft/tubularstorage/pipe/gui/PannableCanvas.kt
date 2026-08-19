package net.kernelpanicsoft.tubularstorage.pipe.gui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import net.kernelpanicsoft.archie.gui.layout.Layout
import net.kernelpanicsoft.archie.gui.layout.MeasurePolicy
import net.kernelpanicsoft.archie.gui.layout.MeasureResult
import net.kernelpanicsoft.archie.gui.modifiers.Constraints
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.input.onDrag
import net.kernelpanicsoft.archie.gui.nodes.UINode
import net.minecraft.client.gui.GuiGraphics
import net.kernelpanicsoft.archie.gui.layout.Renderer

/** Mutable pan offset for a [PannableCanvas] - create via [rememberPannableCanvasState] to survive recomposition. */
@Stable
class PannableCanvasState {
	var panX by mutableStateOf(0)
	var panY by mutableStateOf(0)
}

@Composable
fun rememberPannableCanvasState(): PannableCanvasState = remember { PannableCanvasState() }

/**
 * A fixed-size viewport ([modifier]'s own size) over a single, arbitrarily large child, panned by
 * click-dragging anywhere inside it - the same "pan around a graph" interaction the vanilla
 * advancements screen uses, built on the same [Layout]/[Renderer] primitives
 * [net.kernelpanicsoft.archie.gui.composables.containers.Scrollable] is, minus the scrollbar/
 * lerp animation ([net.kernelpanicsoft.tubularstorage.pipe.gui.CraftingTreeView]'s node graph
 * pans 1:1 with the drag, not eased). Content outside the viewport is clipped via
 * [net.kernelpanicsoft.archie.gui.util.extension.scissor], matching how [net.kernelpanicsoft.archie.gui.composables.containers.Scrollable]
 * hides its own overflowed content.
 */
@Composable
fun PannableCanvas(modifier: Modifier = Modifier, state: PannableCanvasState = rememberPannableCanvasState(), content: @Composable () -> Unit) {
	val measurePolicy = remember {
		MeasurePolicy { _, measurables, constraints ->
			val placeable = measurables.firstOrNull()?.measure(Constraints(minWidth = 0, maxWidth = Int.MAX_VALUE, minHeight = 0, maxHeight = Int.MAX_VALUE))
			val width = if (constraints.maxWidth != Int.MAX_VALUE) constraints.maxWidth else (placeable?.width ?: 0)
			val height = if (constraints.maxHeight != Int.MAX_VALUE) constraints.maxHeight else (placeable?.height ?: 0)
			MeasureResult(width, height) {
				placeable?.placeAt(state.panX, state.panY)
			}
		}
	}

	Layout(
		name = "PannableCanvas",
		measurePolicy = measurePolicy,
		renderer = object : Renderer {
			override fun render(node: UINode, x: Int, y: Int, guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
				guiGraphics.enableScissor(x, y, x + node.width, y + node.height)
			}

			override fun renderAfterChildren(node: UINode, x: Int, y: Int, guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
				guiGraphics.disableScissor()
			}
		},
		modifier = modifier.onDrag<UINode> { _, event ->
			state.panX += event.dragX.toInt()
			state.panY += event.dragY.toInt()
		},
		content = content,
	)
}
