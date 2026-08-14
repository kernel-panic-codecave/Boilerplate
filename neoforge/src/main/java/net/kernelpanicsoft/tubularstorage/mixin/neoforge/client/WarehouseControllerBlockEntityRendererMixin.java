package net.kernelpanicsoft.tubularstorage.mixin.neoforge.client;

import net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity;
import net.kernelpanicsoft.tubularstorage.warehouse.client.WarehouseControllerBlockEntityRenderer;
import net.minecraft.world.phys.AABB;
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
 */
@Mixin(WarehouseControllerBlockEntityRenderer.class)
public class WarehouseControllerBlockEntityRendererMixin {
	public AABB getRenderBoundingBox(WarehouseControllerBlockEntity blockEntity) {
		return AABB.INFINITE;
	}
}
