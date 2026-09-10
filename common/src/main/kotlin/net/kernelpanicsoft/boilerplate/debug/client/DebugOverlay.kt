package net.kernelpanicsoft.boilerplate.debug.client

import com.mojang.blaze3d.vertex.PoseStack
import net.kernelpanicsoft.boilerplate.debug.DebugFlag
import net.kernelpanicsoft.boilerplate.pipe.client.DebugNetworkRenderer
import net.kernelpanicsoft.boilerplate.warehouse.client.WarehouseDebugRenderer
import net.minecraft.client.renderer.MultiBufferSource

/**
 * The client half of the in-world debug overlay: dispatches one world-render frame to each renderer
 * whose own [DebugFlag] is on.
 *
 * Driven by `/bp debug` ([DebugFlags]) rather than by vanilla's F3+B hitbox toggle, which is what
 * this used to hang off. One switch for everything meant the expensive subsystems ran whenever the
 * cheap one was wanted - warehouse snapshots are broadcast to a whole dimension every ten ticks -
 * and it could not be turned on without vanilla's own entity hitboxes coming with it.
 *
 * Both platforms register this at the same point in their world render pass - Fabric's
 * `AFTER_TRANSLUCENT`, NeoForge's `AFTER_TRANSLUCENT_BLOCKS`.
 */
object DebugOverlay {
	fun renderFrame(poseStack: PoseStack, bufferSource: MultiBufferSource) {
		if (!DebugFlags.any) return
		val source = bufferSource as? MultiBufferSource.BufferSource ?: return
		if (DebugFlag.NETWORK in DebugFlags) DebugNetworkRenderer.renderFrame(poseStack, source)
		if (DebugFlag.WAREHOUSE in DebugFlags) WarehouseDebugRenderer.renderFrame(poseStack, source)
	}
}
