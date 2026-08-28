package net.kernelpanicsoft.boilerplate.pipe.client

import com.mojang.blaze3d.vertex.PoseStack
import net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.entity.ItemRenderer
import net.minecraft.core.registries.BuiltInRegistries

/**
 * Renders a [MultipartBlockEntity]'s carried [net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem]s
 * via [TravelingItemRenderer], if [MultipartBlockEntity.pipeBlockId] resolves to a pipe type whose
 * [PipeBlock.showsTravelingItems] is true - a hook attached to a glass pipe keeps showing its
 * contents, one attached to an opaque pipe doesn't, exactly like an unhooked pipe of either type.
 *
 * The pipe body and attached hooks themselves are [MultipartBlockEntityVisual]'s job now, not this
 * renderer's - Flywheel's own engine handles instanced translucency (a [PipeBlock.isTranslucent]
 * pipe body promoted from [net.kernelpanicsoft.boilerplate.pipe.block.GlassPipeBlock]) properly
 * where submitting the same geometry through this renderer's own immediate-mode `tesselateBlock`
 * calls didn't. This renderer keeps running alongside that visual regardless (registered with
 * `neverSkipVanillaRender()` in [net.kernelpanicsoft.boilerplate.registry.TileRegistry]) for
 * exactly this one piece Flywheel isn't a good fit for: an arbitrary, frequently-changing floating
 * icon per in-flight item, not the kind of comparatively stable geometry an instancing engine
 * expects.
 */
class MultipartTravelingItemRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<MultipartBlockEntity> {
	private val itemRenderer: ItemRenderer = context.itemRenderer

	override fun render(
		tile: MultipartBlockEntity,
		partialTick: Float,
		poseStack: PoseStack,
		bufferSource: MultiBufferSource,
		packedLight: Int,
		packedOverlay: Int
	) {
		val level = tile.level ?: return
		if (tile.pipeBlockId == MultipartBlockEntity.NONE) return

		val pipeBlock = BuiltInRegistries.BLOCK.get(tile.pipeBlockId) as? PipeBlock ?: BlockRegistry.Pipe
		if (!pipeBlock.showsTravelingItems) return

		TravelingItemRenderer.render(
			PipeContentsClientCache.get(tile.blockPos),
			itemRenderer,
			level,
			tile.blockPos,
			poseStack,
			bufferSource,
			packedLight,
			packedOverlay,
			partialTick
		)
	}
}
