package net.kernelpanicsoft.boilerplate.client

import net.kernelpanicsoft.boilerplate.pipe.client.DebugNetworkRenderer
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.common.NeoForge

/**
 * Registers the dev route-search overlay into NeoForge's `RenderLevelStageEvent`, on the
 * `AFTER_TRANSLUCENT_BLOCKS` stage - NeoForge's equivalent of the phase the common renderer
 * expects, carrying the level's own pose stack.
 */
object NeoForgeDebugRendering {
	fun register() {
		NeoForge.EVENT_BUS.addListener(NeoForgeDebugRendering::onRenderLevelStage)
	}

	private fun onRenderLevelStage(event: RenderLevelStageEvent) {
		if (event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
		DebugNetworkRenderer.renderFrame(event.poseStack, Minecraft.getInstance().renderBuffers().bufferSource())
	}
}