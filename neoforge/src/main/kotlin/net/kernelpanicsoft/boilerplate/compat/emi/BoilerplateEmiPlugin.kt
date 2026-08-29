package net.kernelpanicsoft.boilerplate.compat.emi

import dev.emi.emi.api.EmiEntrypoint
import dev.emi.emi.api.EmiExclusionArea
import dev.emi.emi.api.EmiPlugin
import dev.emi.emi.api.EmiRegistry
import dev.emi.emi.api.EmiStackProvider
import dev.emi.emi.api.recipe.EmiRecipe
import dev.emi.emi.api.recipe.VanillaEmiRecipeCategories
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler
import dev.emi.emi.api.stack.EmiStack
import dev.emi.emi.api.stack.EmiStackInteraction
import dev.emi.emi.api.widget.Bounds
import net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookScreen
import net.kernelpanicsoft.boilerplate.pipe.gui.CraftingTerminalHookMenu
import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.kernelpanicsoft.boilerplate.util.itemStack
import net.minecraft.world.inventory.Slot

/**
 * EMI is the one of the three recipe viewers with no shared loader-agnostic api artifact (see
 * `docs/design/m6-polish-parity.md`) - this file and `fabric`'s own copy are near-duplicates by
 * necessity, each compiled against that loader's own `:api` jar. Discovery differs per loader too:
 * this class is found via the [EmiEntrypoint] annotation on NeoForge; `fabric`'s own copy is
 * declared under the `"emi"` entrypoint in `fabric.mod.json` instead.
 *
 * Same scope as [net.kernelpanicsoft.boilerplate.compat.rei.BoilerplateREIPlugin]/
 * [net.kernelpanicsoft.boilerplate.compat.jei.BoilerplateJEIPlugin] - only the Crafting Terminal's
 * manual 3x3 grid, the one part of either terminal screen that's both real-vanilla-[Slot]-backed
 * and matches a genuine registered vanilla `CraftingRecipe`. See those classes' KDoc for what's
 * deliberately out of scope.
 */
@EmiEntrypoint
class BoilerplateEmiPlugin : EmiPlugin {
	override fun register(registry: EmiRegistry) {
		registry.addRecipeHandler(
			GuiRegistry.CraftingTerminalHook,
			object : StandardRecipeHandler<CraftingTerminalHookMenu> {
				override fun getInputSources(handler: CraftingTerminalHookMenu): List<Slot> = handler.gridSlots + handler.inventorySlots
				override fun getCraftingSlots(handler: CraftingTerminalHookMenu): List<Slot> = handler.gridSlots
				override fun supportsRecipe(recipe: EmiRecipe): Boolean = recipe.category == VanillaEmiRecipeCategories.CRAFTING
			},
		)

		registry.addExclusionArea(AbstractTerminalHookScreen::class.java) { screen: AbstractTerminalHookScreen<*>, consumer ->
			consumer.accept(Bounds(screen.screenLeft, screen.screenTop, screen.screenWidth, screen.screenHeight))
		}

		registry.addStackProvider(AbstractTerminalHookScreen::class.java) { screen: AbstractTerminalHookScreen<*>, _, _ ->
			screen.hoveredStack?.let { EmiStackInteraction(EmiStack.of(it.itemStack)) } ?: EmiStackInteraction.EMPTY
		}
	}

}
