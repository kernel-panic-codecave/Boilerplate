package net.kernelpanicsoft.tubularstorage.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.kernelpanicsoft.tubularstorage.pipe.client.MultipartHighlightRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reroutes the block hit outline through {@link MultipartHighlightRenderer} for multipart pipe
 * segments: vanilla draws whatever the targeted block's own {@code getShape} returns, which for a
 * multipart is every part at once - hook plates, arms and casing together - so pointing at one
 * face of a hooked pipe outlined the whole assembly. Injecting here (rather than varying
 * {@code getShape} itself) keeps the shape vanilla raytraces against stable, which is what fixed
 * the selection flickering: an outline derived from the previous frame's hit result changes what
 * the next frame's clip hits, and the two keep chasing each other at piece seams.
 * <p>
 * Non-multipart blocks fall straight through to vanilla's own drawing, so this only ever touches
 * Tubular Storage's own segments.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

	@Inject(method = "renderHitOutline", at = @At("HEAD"), cancellable = true)
	private void tubularstorage$renderMultipartOutline(PoseStack poseStack, VertexConsumer consumer, Entity entity, double camX, double camY, double camZ, BlockPos pos, BlockState state, CallbackInfo ci) {
		if (MultipartHighlightRenderer.renderOutline(poseStack, consumer, camX, camY, camZ, entity, pos, state)) {
			ci.cancel();
		}
	}
}
