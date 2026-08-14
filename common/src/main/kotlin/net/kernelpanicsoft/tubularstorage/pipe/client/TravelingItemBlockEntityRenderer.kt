package net.kernelpanicsoft.tubularstorage.pipe.client

import com.mojang.blaze3d.vertex.PoseStack
import net.kernelpanicsoft.tubularstorage.pipe.entity.GlassPipeBlockEntity
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.entity.ItemRenderer

/**
 * Renders a [GlassPipeBlockEntity]'s carried [net.kernelpanicsoft.tubularstorage.pipe.entity.TravelingItem]s
 * via [TravelingItemRenderer] - the point of a "glass" pipe tier over a plain (opaque) one, which
 * never registers this (heavier) renderer at all.
 *
 * Reads [PipeContentsClientCache], the client-authoritative synced snapshot (already dead-reckoned
 * forward there), not [net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity.travelingItems]
 * itself - that field is server-authoritative and never marked `@Sync`, so on the client it's only
 * ever whatever was loaded from disk at the last chunk load, not what's actually in transit now.
 */
class TravelingItemBlockEntityRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<GlassPipeBlockEntity> {
	private val itemRenderer: ItemRenderer = context.itemRenderer

	override fun render(tile: GlassPipeBlockEntity, partialTick: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int, packedOverlay: Int) {
		val level = tile.level ?: return
		TravelingItemRenderer.render(
			PipeContentsClientCache.get(tile.blockPos), itemRenderer, level, tile.blockPos,
			poseStack, bufferSource, packedLight, packedOverlay, partialTick,
		)
	}
}
