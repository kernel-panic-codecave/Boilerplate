package net.kernelpanicsoft.boilerplate.compat.mekanism

import androidx.compose.runtime.Composable
import dev.engine_room.flywheel.api.model.Mesh
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import mekanism.common.util.ChemicalUtil
import net.kernelpanicsoft.boilerplate.client.InstancedMeshes
import net.kernelpanicsoft.boilerplate.client.ResourceDisplayKind
import net.kernelpanicsoft.boilerplate.client.WorldMeshMotion
import net.kernelpanicsoft.boilerplate.client.resourceTooltip
import net.kernelpanicsoft.boilerplate.pipe.gui.SpriteSlotFace
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.item.ItemStack

/**
 * How a Mekanism chemical is **drawn** - the missing half of the chemical kind.
 *
 * Registering a kind's storage and network makes it stored, routed, craftable and listed; without
 * one of these it is all of those things *and invisible*. Chemicals shipped without one, so a
 * terminal listed them as bare slot frames and a withdrawal that arrived correctly looked exactly
 * like a request that never resolved - the resource was sitting in the inbox column with nothing
 * drawing it.
 *
 * A chemical is a sprite and a tint, which is the same shape a fluid is, so both surfaces here are
 * the shared sprite-based ones rather than anything chemical-specific:
 * [net.kernelpanicsoft.boilerplate.pipe.gui.SpriteSlotFace] for the slot and
 * [InstancedMeshes.dropletMesh] for the world.
 *
 * **Client-only**, like every [ResourceDisplayKind] - reached solely through
 * [net.kernelpanicsoft.boilerplate.resource.ResourceKind.display], which nothing on a dedicated
 * server reads, so this class is never loaded there.
 */
object ChemicalDisplayKind : ResourceDisplayKind {

	@Composable
	override fun SlotFace(resource: ResourceComponent, amount: Long, isHovered: Boolean, countText: String?, enabled: Boolean) {
		val chemical = (resource as? ChemicalResource)?.takeIf { !it.isBlank }
		if (chemical == null) {
			SpriteSlotFace(null, 0, isHovered, countText)
			return
		}
		SpriteSlotFace(spriteOf(chemical), slotTint(chemical), isHovered, countText)
	}

	override fun worldMesh(resource: ResourceComponent, amount: Long): Mesh? {
		val chemical = (resource as? ChemicalResource)?.takeIf { !it.isBlank } ?: return null
		// Keyed on the chemical, and the sprite resolved only when that key is new - a pipe carrying
		// a thousand droplets asks for this once per droplet per frame.
		return InstancedMeshes.dropletMesh(chemical.chemical) {
			InstancedMeshes.DropletSkin(spriteOf(chemical) ?: return@dropletMesh null, worldTint(chemical))
		}
	}

	/** Name, millibuckets, and the mod it came from - the same shape a fluid's tooltip has, since a chemical is measured the same way. */
	override fun tooltipLines(resource: ResourceComponent, amount: Long): List<Component> = resourceTooltip(resource) {
		ChemicalUtil.addAttributeTooltips((resource as ChemicalResource).chemical, this::add)
	}

	/** A chemical travels as a droplet exactly as a fluid does, and tumbles for the same reason. */
	override val worldMeshMotion: WorldMeshMotion get() = WorldMeshMotion.TUMBLE

	/**
	 * The chemical held inside a carried tank item, so dropping one into a ghost slot names the
	 * chemical rather than the tank.
	 *
	 * Read straight off the stack's own item capability rather than through
	 * [ChemicalStorageKind.findInItem], which wants a holder to write a modified stack back into -
	 * nothing is being modified here, only looked at.
	 */
	override fun carriedIn(stack: ItemStack): ResourceComponent? {
		if (stack.isEmpty) return null
		val handler = stack.getCapability(MekanismChemicals.ITEM) ?: return null
		for (tank in 0 until handler.chemicalTanks) {
			val held = handler.getChemicalInTank(tank)
			if (!held.isEmpty) return ChemicalResource.of(held)
		}
		return null
	}

	/**
	 * [chemical]'s own texture, off the block atlas - which is where Mekanism stitches its chemical
	 * icons, the same place its own renderer looks them up.
	 *
	 * A chemical this game does not have art for resolves to the missing-texture sprite rather than
	 * `null`, which is the right failure: a visible wrong square beats an invisible resource, which
	 * is the bug this whole class exists to fix.
	 */
	private fun spriteOf(chemical: ChemicalResource): TextureAtlasSprite? =
		Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
			.apply(chemical.chemical.icon ?: MissingTextureAtlasSprite.getLocation())

	/**
	 * [chemical]'s tint for a slot face - fully opaque.
	 *
	 * Mekanism's own renderer reads only the red, green and blue of `getTint()` and supplies its own
	 * alpha, so whatever is in that byte is not a transparency anyone meant: honouring it would draw
	 * some chemicals faintly and others not at all.
	 */
	private fun slotTint(chemical: ChemicalResource): Int = chemical.chemical.tint or (0xFF shl 24)

	/**
	 * [chemical]'s tint for the droplet drawn in the world - translucent for a gas, opaque for
	 * anything else.
	 *
	 * The same distinction Mekanism draws (`MekanismRenderer.getColorARGB` gives a gas an alpha and
	 * everything else a flat `1`), and the reason a mesh has to be able to say it needs blending:
	 * a gas rendered through a cutout material is either solid or invisible, never gaseous.
	 */
	private fun worldTint(chemical: ChemicalResource): Int {
		val rgb = chemical.chemical.tint and 0x00FFFFFF
		val alpha = if (chemical.chemical.isGaseous) GASEOUS_ALPHA else 0xFF
		return rgb or (alpha shl 24)
	}

	/**
	 * How opaque a gaseous chemical's droplet is.
	 *
	 * Mekanism scales a gas's alpha with how full the tank drawing it is; a droplet in flight has no
	 * such fill, so this is a fixed value in the same range - visibly a gas, still solid enough to
	 * read as cargo while it tumbles.
	 */
	private const val GASEOUS_ALPHA = 0xC0
}
