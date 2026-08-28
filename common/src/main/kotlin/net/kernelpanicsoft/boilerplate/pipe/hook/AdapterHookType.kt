package net.kernelpanicsoft.boilerplate.pipe.hook

import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.registry.NetworkTypeRegistry
import net.kernelpanicsoft.boilerplate.util.byDirection
import net.kernelpanicsoft.boilerplate.util.voxelShape
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import net.minecraft.world.phys.shapes.VoxelShape


/**
 * Bridges item pipes' 6x6 cross-section down to a
 * [net.kernelpanicsoft.boilerplate.power.block.PressurePipeBlock]'s slimmer 4x4 one - no
 * tick/menu behavior of its own, but not purely cosmetic either: it's the one hook that can
 * un-gate a [net.kernelpanicsoft.boilerplate.power.network.PressureNetworkBoundary] edge, which
 * is a boundary by default in either of two cases - an item-pipe segment always conducts pressure
 * among itself and into any directly adjacent [net.kernelpanicsoft.boilerplate.power.PressureApi]
 * block ([net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock.secondaryNetworkTypes]), but the
 * edge where it touches a *dedicated* [net.kernelpanicsoft.boilerplate.power.block.PressurePipeBlock]
 * run doesn't merge without one; and [net.kernelpanicsoft.boilerplate.pipe.network.SubnetBoundary]'s
 * own item-network split (an [InterfaceHookType] hook facing another hook) is a pressure boundary
 * too, same reasoning - the two sides are logically separate networks, and pressure crossing there
 * for free would undermine that as much as an item silently routing through would. Attach an
 * Adapter facing whichever side needs bridging.
 */
object AdapterHookType : PipeHookType<AdapterHookState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "adapter"

	override val id: ResourceLocation get() = ID

	/** Attachable only on an item-pipe segment (see [net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]). */
	override val compatibleNetworkTypes = setOf(NetworkTypeRegistry.Item)

	/** [PipeHookType.basePressureCost] - No active per-tick work of its own either - just the network-bridging role - so the same lightest idle draw as the other passive/config-only hooks. */
	override val basePressureCost: Long = 1L

	override fun createState(): AdapterHookState = AdapterHookState()

	override fun asItem(): Item = ItemRegistry.AdapterHook

	override val shapesByDirection: Map<Direction, VoxelShape> by lazy {
		voxelShape {
			box(0.3125, 0.3125, 0.1875, 0.6875, 0.375, 0.3125)
			box(0.3125, 0.625, 0.1875, 0.6875, 0.6875, 0.3125)
			box(0.3125, 0.375, 0.1875, 0.375, 0.625, 0.3125)
			box(0.625, 0.375, 0.1875, 0.6875, 0.625, 0.3125)
			box(0.375, 0.375, 0.0, 0.625, 0.4375, 0.1875)
			box(0.375, 0.5625, 0.0, 0.625, 0.625, 0.1875)
			box(0.375, 0.4375, 0.0, 0.4375, 0.5625, 0.1875)
			box(0.5625, 0.4375, 0.0, 0.625, 0.5625, 0.1875)
		}.byDirection
	}
}
