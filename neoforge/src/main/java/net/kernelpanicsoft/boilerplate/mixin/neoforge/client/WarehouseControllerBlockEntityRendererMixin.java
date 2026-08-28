package net.kernelpanicsoft.boilerplate.mixin.neoforge.client;

import androidx.annotation.NonNull;
import net.kernelpanicsoft.boilerplate.warehouse.Bounds;
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity;
import net.kernelpanicsoft.boilerplate.warehouse.client.WarehouseControllerBlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.extensions.IBlockEntityRendererExtension;
import org.spongepowered.asm.mixin.Mixin;

/**
 * NeoForge's {@code IBlockEntityRendererExtension} adds its own {@code getRenderBoundingBox}
 * check, defaulting to the block's own unit cube, on top of vanilla's
 * {@link net.minecraft.client.renderer.blockentity.BlockEntityRenderer#shouldRenderOffScreen}
 * (which {@link WarehouseControllerBlockEntityRenderer} already overrides) - so on NeoForge
 * specifically the gantry's crossbeams/rod still vanish the instant the controller block itself
 * leaves the frustum, since that extension interface isn't visible from {@code common}'s compile
 * classpath at all (it compiles against a plain vanilla {@code BlockEntityRenderer}, not NeoForge's
 * patched one) and can't be overridden there directly. {@code BlockEntityRenderer} extends that
 * interface at NeoForge runtime regardless of what {@code common} saw at compile time, so this
 * method - not shadowing or overwriting anything already present in the target class's own
 * bytecode, just a plain new method with the exact inherited signature - resolves ahead of the
 * interface's default implementation once woven in, the same way any class's own declared method
 * takes priority over an inherited default.
 * <p>
 * Scoped to the warehouse's own bound volume rather than {@link AABB#INFINITE} - the gantry can
 * never draw outside it, so this never wrongly culls the render, but it still lets the camera
 * genuinely looking elsewhere skip the actual per-frame work (AO calculation, dead reckoning, quad
 * emission) that an always-visible box would force regardless of where the camera's pointed.
 */
@Mixin(WarehouseControllerBlockEntityRenderer.class)
public class WarehouseControllerBlockEntityRendererMixin implements IBlockEntityRendererExtension<WarehouseControllerBlockEntity> {
	@NonNull
	@Override
	public AABB getRenderBoundingBox(@NonNull WarehouseControllerBlockEntity blockEntity) {
		Bounds bounds = blockEntity.getBounds();
		if (bounds == null) return new AABB(blockEntity.getBlockPos());
		BlockPos min = bounds.getMin();
		BlockPos max = bounds.getMax();
		return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
	}
}
