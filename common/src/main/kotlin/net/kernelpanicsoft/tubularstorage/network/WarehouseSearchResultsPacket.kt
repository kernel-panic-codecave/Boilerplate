package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SItemStack
import net.kernelpanicsoft.tubularstorage.pipe.gui.WarehouseTerminalMenu
import net.minecraft.client.Minecraft

/**
 * Server -> client: the aggregated contents of every warehouse a
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.WarehouseTerminalHookType]'s menu can currently
 * reach, one [ItemStack][net.minecraft.world.item.ItemStack] per distinct resource with its total
 * count as the stack's own count (not a real placeable stack - just reusing the existing
 * [SItemStack] wire format instead of inventing an `ItemResource` serializer). Applied to whichever
 * [WarehouseTerminalMenu] the receiving player currently has open, if any - sent unprompted after a
 * menu opens or a withdrawal, or in response to
 * [RequestWarehouseSearchResultsPacket].
 */
@Serializable
data class WarehouseSearchResultsPacket(val results: List<SItemStack>) {
	fun handleOnClient() {
		val menu = Minecraft.getInstance().player?.containerMenu as? WarehouseTerminalMenu ?: return
		menu.updateResults(results)
	}
}
