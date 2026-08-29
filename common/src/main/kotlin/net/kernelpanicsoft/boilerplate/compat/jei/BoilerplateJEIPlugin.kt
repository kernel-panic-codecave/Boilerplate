package net.kernelpanicsoft.boilerplate.compat.jei

import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.constants.RecipeTypes
import mezz.jei.api.gui.builder.IClickableIngredientFactory
import mezz.jei.api.gui.handlers.IGuiContainerHandler
import mezz.jei.api.registration.IGuiHandlerRegistration
import mezz.jei.api.registration.IRecipeTransferRegistration
import mezz.jei.api.runtime.IClickableIngredient
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookScreen
import net.kernelpanicsoft.boilerplate.pipe.gui.CraftingTerminalHookMenu
import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.kernelpanicsoft.boilerplate.util.itemStack
import net.minecraft.client.renderer.Rect2i
import net.minecraft.resources.ResourceLocation
import java.util.Optional

/**
 * JEI's own `common-api` artifact (`mezz.jei:jei-1.21.1-common-api`) is loader-agnostic, same as
 * REI's - see `compat/rei/BoilerplateREIPlugin`'s own KDoc for the shared reasoning, and
 * `docs/design/m6-polish-parity.md` for why EMI alone needs a real per-loader split. JEI discovers
 * this plugin itself, by scanning installed mods' classes for [JeiPlugin] - unlike REI, no
 * `META-INF/services`/entrypoint shim is needed on either loader, and the class is never loaded at
 * all unless JEI is actually installed.
 *
 * Same scope as [net.kernelpanicsoft.boilerplate.compat.rei.BoilerplateREIPlugin]: only the
 * Crafting Terminal's manual 3x3 grid, since it's the only part of either terminal screen that's
 * both real-vanilla-[net.minecraft.world.inventory.Slot]-backed and matches a genuine registered
 * vanilla `CraftingRecipe`. See that class's KDoc for what's deliberately out of scope.
 */
@JeiPlugin
class BoilerplateJEIPlugin : IModPlugin {
	override fun getPluginUid(): ResourceLocation = UID

	/** Lets JEI's own "transfer recipe" button fill [CraftingTerminalHookMenu.registerSlotHandlers]'s real `grid` slots from a shown vanilla crafting recipe, exactly as it already does for a real crafting table. */
	override fun registerRecipeTransferHandlers(registration: IRecipeTransferRegistration) {
		registration.addRecipeTransferHandler(
			CraftingTerminalHookMenu::class.java,
			GuiRegistry.CraftingTerminalHook,
			RecipeTypes.CRAFTING,
			GRID_SLOT_START,
			GRID_SLOT_COUNT,
			INVENTORY_SLOT_START,
			INVENTORY_SLOT_COUNT,
		)
	}

	/** Keeps JEI's own item panel off every terminal screen's real occupied rectangle, and surfaces [AbstractTerminalHookScreen.hoveredStack] - the Store list's own non-slot hover tracking - to JEI's "view recipes"/"view uses" hover lookup, both otherwise only inferred from real vanilla [net.minecraft.world.inventory.Slot]s. */
	override fun registerGuiHandlers(registration: IGuiHandlerRegistration) {
		registration.addGenericGuiContainerHandler(
			AbstractTerminalHookScreen::class.java,
			object : IGuiContainerHandler<AbstractTerminalHookScreen<*>> {
				override fun getGuiExtraAreas(containerScreen: AbstractTerminalHookScreen<*>): List<Rect2i> =
					listOf(Rect2i(containerScreen.screenLeft, containerScreen.screenTop, containerScreen.screenWidth, containerScreen.screenHeight))

				override fun getClickableIngredientUnderMouse(
					builder: IClickableIngredientFactory,
					containerScreen: AbstractTerminalHookScreen<*>,
					mouseX: Double,
					mouseY: Double,
				): Optional<out IClickableIngredient<*>> {
					val stack = containerScreen.hoveredStack ?: return Optional.empty()
					val area = Rect2i((mouseX - HOVER_AREA_RADIUS).toInt(), (mouseY - HOVER_AREA_RADIUS).toInt(), HOVER_AREA_RADIUS * 2, HOVER_AREA_RADIUS * 2)
					return builder.createBuilder(stack.itemStack).buildWithArea(area)
				}
			},
		)
	}

	companion object {
		private val UID = ResourceLocation.fromNamespaceAndPath(Boilerplate.MOD_ID, "jei_plugin")
		private const val GRID_SLOT_START = 9
		private const val GRID_SLOT_COUNT = 9
		private const val INVENTORY_SLOT_START = 18
		private const val INVENTORY_SLOT_COUNT = 36
		private const val HOVER_AREA_RADIUS = 8
	}
}
