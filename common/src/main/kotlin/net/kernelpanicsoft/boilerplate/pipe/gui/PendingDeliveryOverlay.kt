package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.Composable
import net.kernelpanicsoft.archie.gui.composables.input.Clickable
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.BoxMeasurePolicy
import net.kernelpanicsoft.archie.gui.layout.Layout
import net.kernelpanicsoft.archie.gui.layout.Renderer
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.size
import net.kernelpanicsoft.archie.gui.nodes.UINode
import net.kernelpanicsoft.archie.gui.util.extension.invoke
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.network.CancelPendingDeliveryPacket
import net.kernelpanicsoft.boilerplate.pipe.hook.PendingDelivery
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.RenderType
import kotlin.math.ceil
import kotlin.math.floor

/** Semi-transparent red - the same [net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.renderSlotHighlight] draw vanilla's own hover overlay uses, just red instead of vanilla's white, since hovering a reserved-slot placeholder means "click to cancel" rather than "click to pick up". */
private const val CANCEL_HIGHLIGHT_COLOR = 0x80FF3333.toInt()

/**
 * Draws as a stand-in over a still-empty [net.kernelpanicsoft.boilerplate.pipe.hook.TerminalHookState.output]
 * slot for one in-flight [delivery] - see [PendingDelivery]'s own KDoc for why the real slot
 * underneath stays genuinely empty the whole time. Three layers, all driven by client-only state
 * (nothing here touches the real, still-empty [net.minecraft.world.inventory.Slot]):
 *
 * 1. A fake item icon for [PendingDelivery.resource]/[PendingDelivery.amount].
 * 2. A countdown wipe using the exact same draw call vanilla's own per-`Item` cooldown overlay
 *    does ([net.minecraft.client.gui.GuiGraphics.renderItemDecorations]) - just driven by
 *    [PendingDelivery.startTick]/[PendingDelivery.totalTicks] against the client's own current
 *    tick instead of a real [net.minecraft.world.item.ItemCooldowns] entry, since nothing about
 *    this delivery is tied to a real cooldown on the resource's own item type.
 * 3. [CANCEL_HIGHLIGHT_COLOR] on hover in place of vanilla's own slot-highlight white, plus a
 *    click that fires [CancelPendingDeliveryPacket] - see that packet's own KDoc for what
 *    cancelling actually does to the item already in flight.
 */
@Composable
fun PendingDeliveryOverlay(delivery: PendingDelivery) {
	Clickable(
		onClick = { BoilerplateNetworkChannel.toServer(CancelPendingDeliveryPacket(delivery.id)) },
		showHandCursor = true,
	) { isHovered, _, _ ->
		Layout(
			name = "PendingDeliveryOverlay",
			measurePolicy = BoxMeasurePolicy(Alignment.Center),
			modifier = Modifier.size(18, 18),
			renderer = object : Renderer {
				override fun render(
					node: UINode, x: Int, y: Int,
					guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float,
				) = guiGraphics {
					val itemX = x + 1
					val itemY = y + 1
					val stack = delivery.resource.toStack(delivery.amount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
					renderItem(stack, itemX, itemY)

					val gameTime = Minecraft.getInstance().level?.gameTime ?: delivery.startTick
					val elapsed = (gameTime - delivery.startTick).toFloat() + partialTick
					val remaining = (1f - elapsed / delivery.totalTicks.coerceAtLeast(1)).coerceIn(0f, 1f)
					if (remaining > 0f) {
						val top = itemY + floor(16f * (1f - remaining)).toInt()
						val bottom = top + ceil(16f * remaining).toInt()
						fill(RenderType.guiOverlay(), itemX, top, itemX + 16, bottom, Int.MAX_VALUE)
					}

					if (isHovered) fillGradient(RenderType.guiOverlay(), itemX, itemY, itemX + 16, itemY + 16, CANCEL_HIGHLIGHT_COLOR, CANCEL_HIGHLIGHT_COLOR, 0)
				}
			},
		)
	}
}
