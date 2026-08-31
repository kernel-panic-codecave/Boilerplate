package net.kernelpanicsoft.boilerplate.client

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.kernelpanicsoft.boilerplate.pipe.client.DebugNetworkRenderer

/**
 * Registers the dev route-search overlay into Fabric's world render pass, right after the
 * translucent level section - the same phase the common renderer expects, with the level's own
 * pose stack and buffer source.
 */
object FabricDebugRendering {
	fun register() {
		WorldRenderEvents.AFTER_TRANSLUCENT.register(FabricDebugRendering::onRenderAfterTranslucent)
	}

	private fun onRenderAfterTranslucent(context: WorldRenderContext) {
		val consumers = context.consumers() ?: return
		val poseStack = context.matrixStack() ?: return
		DebugNetworkRenderer.renderFrame(poseStack, consumers)
	}
}