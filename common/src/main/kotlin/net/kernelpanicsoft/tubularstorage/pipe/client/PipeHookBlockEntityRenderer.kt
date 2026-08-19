package net.kernelpanicsoft.tubularstorage.pipe.client

import com.mojang.blaze3d.vertex.PoseStack
import net.kernelpanicsoft.tubularstorage.pipe.block.PipeBlock
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.entity.ItemRenderer
import net.minecraft.core.registries.BuiltInRegistries

/**
 * Renders a [HookBlockEntity]'s carried [net.kernelpanicsoft.tubularstorage.pipe.entity.TravelingItem]s
 * via [TravelingItemRenderer], if [HookBlockEntity.pipeBlockId] resolves to a pipe type whose
 * [PipeBlock.showsTravelingItems] is true - a hook attached to a glass pipe keeps showing its
 * contents, one attached to an opaque pipe doesn't, exactly like an unhooked pipe of either type.
 *
 * The pipe body and attached hooks themselves are [HookBlockEntityVisual]'s job now, not this
 * renderer's - Flywheel's own engine handles instanced translucency (a [PipeBlock.isTranslucent]
 * pipe body promoted from [net.kernelpanicsoft.tubularstorage.pipe.block.GlassPipeBlock]) properly
 * where submitting the same geometry through this renderer's own immediate-mode `tesselateBlock`
 * calls didn't. This renderer keeps running alongside that visual regardless (registered with
 * `neverSkipVanillaRender()` in [net.kernelpanicsoft.tubularstorage.registry.TileRegistry]) for
 * exactly this one piece Flywheel isn't a good fit for: an arbitrary, frequently-changing floating
 * icon per in-flight item, not the kind of comparatively stable geometry an instancing engine
 * expects.
 */
class PipeHookBlockEntityRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<HookBlockEntity> {
	private val itemRenderer: ItemRenderer = context.itemRenderer

	override fun render(
		tile: HookBlockEntity,
		partialTick: Float,
		poseStack: PoseStack,
		bufferSource: MultiBufferSource,
		packedLight: Int,
		packedOverlay: Int
	) {
		val level = tile.level ?: return
		if (tile.pipeBlockId == HookBlockEntity.NONE) return

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
