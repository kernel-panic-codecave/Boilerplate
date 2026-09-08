package net.kernelpanicsoft.boilerplate.registry

import androidx.compose.runtime.Composable
import dev.architectury.platform.Platform
import dev.engine_room.flywheel.api.model.Mesh
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.util.buildComponent
import net.kernelpanicsoft.archie.util.minecraftClient
import net.kernelpanicsoft.boilerplate.client.InstancedMeshes
import net.kernelpanicsoft.boilerplate.client.ResourceDisplayKind
import net.kernelpanicsoft.boilerplate.pipe.gui.FakeSlot
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

/**
 * The item kind's visuals: a stack in a slot, its own baked model in the world.
 *
 * Thin, as every [ResourceDisplayKind] is - the slot face is an existing composable and the world
 * mesh is [InstancedMeshes]' own baking, so what actually lives here is the mapping from a kind to
 * its two surfaces.
 *
 * **Client-only.** A dedicated server never reads [net.kernelpanicsoft.boilerplate.network.ResourceKind.display],
 * so this class is never loaded there - which is why [ItemKind] exposes it through a getter rather
 * than an initialised property.
 */
object ItemDisplayKind : ResourceDisplayKind {
	@Composable
	override fun SlotFace(resource: ResourceComponent, amount: Long, isHovered: Boolean, countText: String?, enabled: Boolean) {
		val item = resource as? ItemResource
		FakeSlot(if (item == null || item.isBlank) null else ResourceStack(item, amount), isHovered, countText, enabled)
	}

	override fun worldMesh(resource: ResourceComponent, amount: Long): Mesh? =
		(resource as? ItemResource)?.takeIf { !it.isBlank }?.let { InstancedMeshes.itemMesh(it.toStack(1)) }

	/** An item carried in an [ItemStack] is simply itself. */
	override fun carriedIn(stack: ItemStack): ResourceComponent? =
		if (stack.isEmpty) null else ItemResource.of(stack)

	/**
	 * Vanilla's own tooltip for the stack, whole - enchantments, durability, lore, the
	 * advanced-tooltip registry id, and whatever other mods have attached to it.
	 *
	 * Built at a count of one: this is a description of the *thing*, and the amount beside it in a
	 * terminal is a network total that no item's own tooltip has any business restating.
	 */
	override fun tooltipLines(resource: ResourceComponent, amount: Long): List<Component> {
		val item = (resource as? ItemResource)?.takeIf { !it.isBlank } ?: return super.tooltipLines(resource, amount)
		val minecraft = minecraftClient
		return item.toStack(1).getTooltipLines(
			Item.TooltipContext.of(minecraft.level),
			minecraft.player,
			if (minecraft.options.advancedItemTooltips) TooltipFlag.ADVANCED else TooltipFlag.NORMAL,
		).apply {
			ItemKind.registryId(resource)?.namespace?.let { namespace ->
				val name = Platform.getOptionalMod(namespace).map { it.name }.orElse(namespace)!!
				val component = buildComponent {
					style {
						color = TextColor.fromLegacyFormat(ChatFormatting.BLUE)
						italic = true
					}
					text(name)
				}
				if (component !in last())
					add(component)
			}
		}
	}
}
