package net.kernelpanicsoft.boilerplate.client

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.kernelpanicsoft.boilerplate.debug.client.DebugOverlay

/**
 * Registers the in-world debug overlay into Fabric's world render pass, right after the
 * translucent level section - the same phase the common renderers expect, with the level's own
 * pose stack and buffer source. [DebugOverlay] owns the toggle and fans the frame out to every
 * renderer that draws into it.
 */
object FabricDebugRendering {
	fun register() {
		WorldRenderEvents.AFTER_TRANSLUCENT.register(FabricDebugRendering::onRenderAfterTranslucent)
	}

	private fun onRenderAfterTranslucent(context: WorldRenderContext) {
		val consumers = context.consumers() ?: return
		val poseStack = context.matrixStack() ?: return
		DebugOverlay.renderFrame(poseStack, consumers)
	}
}