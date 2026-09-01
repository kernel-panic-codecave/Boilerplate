package net.kernelpanicsoft.boilerplate.client

import net.kernelpanicsoft.boilerplate.debug.client.DebugOverlay
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.common.NeoForge

/**
 * Registers the in-world debug overlay into NeoForge's `RenderLevelStageEvent`, on the
 * `AFTER_TRANSLUCENT_BLOCKS` stage - NeoForge's equivalent of the phase the common renderers
 * expect, carrying the level's own pose stack. [DebugOverlay] owns the toggle and fans the frame
 * out to every renderer that draws into it.
 */
object NeoForgeDebugRendering {
	fun register() {
		NeoForge.EVENT_BUS.addListener(NeoForgeDebugRendering::onRenderLevelStage)
	}

	private fun onRenderLevelStage(event: RenderLevelStageEvent) {
		if (event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
		DebugOverlay.renderFrame(event.poseStack, Minecraft.getInstance().renderBuffers().bufferSource())
	}
}