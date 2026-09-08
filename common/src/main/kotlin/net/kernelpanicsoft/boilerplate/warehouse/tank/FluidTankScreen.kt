package net.kernelpanicsoft.boilerplate.warehouse.tank

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import dev.architectury.fluid.FluidStack
import earth.terrarium.common_storage_lib.resources.ResourceStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.fluid.util.FluidAmounts
import net.kernelpanicsoft.archie.gui.ComposeContainerScreen
import net.kernelpanicsoft.archie.gui.blockentity.observeProperty
import net.kernelpanicsoft.archie.gui.composables.basic.FluidTank
import net.kernelpanicsoft.archie.gui.composables.basic.Text
import net.kernelpanicsoft.archie.gui.composables.containers.ContainerPanel
import net.kernelpanicsoft.archie.gui.layout.Alignment
import net.kernelpanicsoft.archie.gui.layout.Arrangement
import net.kernelpanicsoft.archie.gui.layout.Column
import net.kernelpanicsoft.archie.gui.layout.Row
import net.kernelpanicsoft.archie.gui.modifiers.Modifier
import net.kernelpanicsoft.archie.gui.modifiers.height
import net.kernelpanicsoft.archie.gui.modifiers.width
import net.kernelpanicsoft.archie.gui.theme.LocalTheme
import net.kernelpanicsoft.archie.transfer.ArchieFluidStorage
import net.kernelpanicsoft.boilerplate.gui.BoilerplateTheme
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * The fluid tank's screen: what it holds, how much, and how full it is.
 *
 * The mod's first fluid-native GUI. Everything it needs already existed - Archie's [FluidTank]
 * widget draws the fluid's own still sprite through `AFluidRenderPlatform`, which is the same
 * loader seam the in-pipe droplet renderer goes through - so the work here was syncing the
 * contents, not drawing them.
 *
 * Volumes are shown in **millibuckets**, converted from whatever the platform counts internally.
 * That is the unit a player thinks in, and the internal one differs per loader (81,000 droplets to
 * the bucket on Fabric, 1,000 mB on NeoForge), so showing raw platform units would print a
 * different number on each.
 */
class FluidTankScreen(private val menu: FluidTankMenu, playerInventory: Inventory, title: Component) :
	ComposeContainerScreen<FluidTankMenu>(menu, playerInventory, title) {

	init {
		start { content() }
	}

	@Composable
	fun content() {
		val theme = LocalTheme.current
		val stored = tankContents()
		val resource = stored.resource
		val capacityMb = FluidAmounts.toMillibuckets(menu.capacity)
		val storedMb = FluidAmounts.toMillibuckets(stored.amount)
		// Amount and capacity are both handed over in platform units - the widget only takes their
		// ratio, so the two just have to agree with each other, and converting either to
		// millibuckets first would only lose precision.
		val shown = if (resource.isBlank) FluidStack.empty() else FluidStack.create(resource.type, stored.amount)


        BoilerplateTheme {
			ContainerPanel(contentWidth = CONTENT_WIDTH) {
				Row(horizontalArrangement = Arrangement.spacedBy(8), verticalAlignment = Alignment.CenterVertically) {
					FluidTank(
						fluid = shown,
						capacity = menu.capacity,
						modifier = Modifier.width(TANK_WIDTH).height(TANK_HEIGHT),
					)
					Column(verticalArrangement = Arrangement.spacedBy(4)) {
						Text(
							// A blank resource has no registry key at all, so it is named rather than
							// looked up - the readout should say, "Empty", not print a null key.
							if (resource.isBlank) Component.literal("Empty")
							else Component.literal(BuiltInRegistries.FLUID.getKey(resource.type).path.replace('_', ' ')),
							dropShadow = false,
							color = theme.darkTextColor,
						)
						Text(Component.literal("$storedMb / $capacityMb mB"), dropShadow = false, color = theme.darkTextColor)
						Text(Component.literal("${percentFull(stored.amount, menu.capacity)}% full"), dropShadow = false, color = theme.darkTextColor)
					}
				}
			}
		}
	}

	/**
	 * The tank's contents as Compose state.
	 *
	 * `observeProperty` is Archie's bridge for a `@Sync`'d block-entity property: the state container
	 * pushes each change into a Compose `MutableState`, so the gauge recomposes when the tank changes
	 * and not otherwise. Reading `menu.tile.storage` directly would render once and never again - the
	 * storage is an ordinary object, and Compose has no way to know it moved.
	 *
	 * Falls back to the menu's own view of the storage until the first packet lands, which is a frame
	 * or two after opening.
	 */
	@Composable
	private fun tankContents(): ResourceStack<FluidResource> {
		val synced by observeProperty<ArchieFluidStorage>(SYNCED_STORAGE_PROPERTY)
		val slot = synced?.get(0) ?: menu.stored
		return ResourceStack(slot.resource, slot.amount)
	}

	companion object {
		/** Snake-cased name of [FluidTankBlockEntity.storage] - what Archie keys the synced property by. */
		private const val SYNCED_STORAGE_PROPERTY = "storage"

		private const val CONTENT_WIDTH = 18 * 9
		private const val TANK_WIDTH = 24
		private const val TANK_HEIGHT = 64

		/** Rounded down, so a tank holding a single droplet reads `0%` rather than claiming to be `1%` full. */
		internal fun percentFull(stored: Long, capacity: Long): Int =
			if (capacity <= 0L) 0 else ((stored.toDouble() / capacity) * 100).toInt().coerceIn(0, 100)
	}
}
