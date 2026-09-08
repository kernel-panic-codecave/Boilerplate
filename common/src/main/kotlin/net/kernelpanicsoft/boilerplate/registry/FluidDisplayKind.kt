package net.kernelpanicsoft.boilerplate.registry

import androidx.compose.runtime.Composable
import dev.engine_room.flywheel.api.model.Mesh
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import net.kernelpanicsoft.boilerplate.client.InstancedMeshes
import net.kernelpanicsoft.boilerplate.client.ResourceDisplayKind
import net.kernelpanicsoft.boilerplate.client.WorldMeshMotion
import net.kernelpanicsoft.boilerplate.client.resourceTooltip
import net.kernelpanicsoft.boilerplate.pipe.gui.FluidSlotFace
import net.kernelpanicsoft.boilerplate.pipe.gui.fluidIn
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/**
 * The fluid kind's visuals: a tinted still sprite in a slot, a droplet in the world.
 *
 * **Client-only**, for the reason [ItemDisplayKind] documents.
 */
object FluidDisplayKind : ResourceDisplayKind {
	/** [enabled] is ignored: a fluid face is its own still sprite, which has no greyed-out variant to switch to. */
	@Composable
	override fun SlotFace(resource: ResourceComponent, amount: Long, isHovered: Boolean, countText: String?, enabled: Boolean) {
		val fluid = resource as? FluidResource ?: return
		FluidSlotFace(fluid, isHovered, countText)
	}

	override fun worldMesh(resource: ResourceComponent, amount: Long): Mesh? =
		(resource as? FluidResource)?.takeIf { !it.isBlank }?.let { InstancedMeshes.fluidMesh(it) }

	/** A droplet of fluid has no upright to keep - see [WorldMeshMotion.TUMBLE]. */
	override val worldMeshMotion: WorldMeshMotion get() = WorldMeshMotion.TUMBLE

	/**
	 * The fluid held inside a carried bucket (or any other fluid-containing item), so dropping one
	 * into a ghost slot names the fluid rather than the container.
	 *
	 * Probed through the item's own fluid capability against a one-slot holder, which is what makes
	 * this work for any container item rather than just vanilla buckets.
	 */
	override fun carriedIn(stack: ItemStack): ResourceComponent? = fluidIn(stack)

	/** Name, millibuckets, and the mod it came from - see [resourceTooltip]. */
	override fun tooltipLines(resource: ResourceComponent, amount: Long): List<Component> = resourceTooltip(resource)
}
