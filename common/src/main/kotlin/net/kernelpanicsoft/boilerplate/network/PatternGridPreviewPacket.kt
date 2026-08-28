package net.kernelpanicsoft.boilerplate.network

import kotlinx.serialization.Serializable
import net.kernelpanicsoft.archie.serialization.serializers.SItemStack
import net.kernelpanicsoft.boilerplate.pipe.gui.CraftingTerminalHookMenu
import net.kernelpanicsoft.boilerplate.pipe.gui.PatternTerminalHookMenu
import net.minecraft.client.Minecraft

/**
 * Server -> client: reply to [RequestCraftGridPreviewPacket] - what
 * [net.kernelpanicsoft.boilerplate.pipe.hook.CraftingTerminalHookState.grid]'s current contents
 * would assemble into right now (vanilla's own recipe match, [net.minecraft.world.item.ItemStack.EMPTY]
 * if none), the live preview a real vanilla crafting table's own result slot shows - see
 * [CraftingTerminalHookMenu.craftOnce].
 */
@Serializable
data class PatternGridPreviewPacket(val resultStack: SResourceStack<SItemResource>) {
	fun handleOnClient() {
		val menu = Minecraft.getInstance().player?.containerMenu as? PatternTerminalHookMenu ?: return
		menu.updateGridPreview(resultStack)
	}
}
