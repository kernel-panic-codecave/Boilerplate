package net.kernelpanicsoft.boilerplate.debug.client

import com.mojang.blaze3d.vertex.PoseStack
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.network.DebugOverlayTogglePacket
import net.kernelpanicsoft.boilerplate.pipe.client.DebugNetworkRenderer
import net.kernelpanicsoft.boilerplate.warehouse.client.WarehouseDebugRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource

/**
 * The client half of the in-world debug overlay: owns the toggle, and dispatches one world-render
 * frame to every renderer that draws into it.
 *
 * Tied to vanilla's F3+B hitbox toggle - each flip of `EntityRenderDispatcher`'s hitbox flag shows
 * or hides the whole overlay and announces the state to the server (see [DebugOverlayTogglePacket]),
 * so route-search tracing and both snapshot broadcasts only run while at least one player is
 * actually looking, on any environment. That flip is detected here rather than inside any one
 * renderer, so adding a second one doesn't send the server a second copy of every toggle.
 *
 * Both platforms register this at the same point in their world render pass - Fabric's
 * `AFTER_TRANSLUCENT`, NeoForge's `AFTER_TRANSLUCENT_BLOCKS`.
 */
object DebugOverlay {
	/** The previous frame's hitbox flag, so a flip is announced to the server exactly once. Render-thread-only. */
	private var lastHitBoxes = false

	fun renderFrame(poseStack: PoseStack, bufferSource: MultiBufferSource) {
		val hitBoxes = Minecraft.getInstance().entityRenderDispatcher.shouldRenderHitBoxes()
		if (hitBoxes != lastHitBoxes) {
			lastHitBoxes = hitBoxes
			BoilerplateNetworkChannel.toServer(DebugOverlayTogglePacket(hitBoxes))
		}
		if (!hitBoxes) return

		val source = bufferSource as? MultiBufferSource.BufferSource ?: return
		DebugNetworkRenderer.renderFrame(poseStack, source)
		WarehouseDebugRenderer.renderFrame(poseStack, source)
	}
}
