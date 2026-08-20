package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SItemStack
import net.kernelpanicsoft.tubularstorage.pipe.gui.AbstractTerminalHookMenu
import net.minecraft.client.Minecraft

/**
 * Server -> client: the aggregated contents of every warehouse a
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.TerminalHookType]'s menu can currently
 * reach, one [ItemStack][net.minecraft.world.item.ItemStack] per distinct resource with its total
 * count as the stack's own count (not a real placeable stack - just reusing the existing
 * [SItemStack] wire format instead of inventing an `ItemResource` serializer). Applied to whichever
 * [AbstractTerminalHookMenu] the receiving player currently has open, if any - sent unprompted after a
 * menu opens or a withdrawal, or in response to
 * [RequestTerminalSearchResultsPacket].
 */
@Serializable
data class TerminalSearchResultsPacket(val results: List<SResourceStack<SItemResource>>) {
	fun handleOnClient() {
		val menu = Minecraft.getInstance().player?.containerMenu as? AbstractTerminalHookMenu<*> ?: return
		menu.updateResults(results)
	}
}
