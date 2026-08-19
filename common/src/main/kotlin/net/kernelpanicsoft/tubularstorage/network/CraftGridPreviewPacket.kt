package net.kernelpanicsoft.tubularstorage.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SItemStack
import net.kernelpanicsoft.tubularstorage.pipe.gui.CraftingTerminalHookMenu
import net.minecraft.client.Minecraft

/**
 * Server -> client: reply to [RequestCraftGridPreviewPacket] - what
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.CraftingTerminalHookState.grid]'s current contents
 * would assemble into right now (vanilla's own recipe match, [net.minecraft.world.item.ItemStack.EMPTY]
 * if none), the live preview a real vanilla crafting table's own result slot shows - see
 * [CraftingTerminalHookMenu.craftOnce].
 */
@Serializable
data class CraftGridPreviewPacket(val resultStack: SItemStack) {
	fun handleOnClient() {
		val menu = Minecraft.getInstance().player?.containerMenu as? CraftingTerminalHookMenu ?: return
		menu.updateGridPreview(resultStack)
	}
}
