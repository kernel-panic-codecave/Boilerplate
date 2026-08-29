package net.kernelpanicsoft.boilerplate.compat.jei

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.constants.RecipeTypes
import mezz.jei.api.gui.builder.IClickableIngredientFactory
import mezz.jei.api.gui.handlers.IGuiContainerHandler
import mezz.jei.api.gui.ingredient.IRecipeSlotsView
import mezz.jei.api.recipe.transfer.IRecipeTransferError
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler
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
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.crafting.CraftingRecipe
import net.minecraft.world.item.crafting.RecipeHolder
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

	/**
	 * Lets JEI's own "transfer recipe" button fill [CraftingTerminalHookMenu.registerSlotHandlers]'s
	 * real `grid` slots from a shown vanilla crafting recipe, exactly as it already does for a real
	 * crafting table - [IRecipeTransferHandlerHelper.createUnregisteredRecipeTransferHandler]'s own
	 * default logic ([basicInfo]), untouched, wrapped so a click first asks the terminal to top the
	 * player's own inventory up with anything missing that it can reach (storage, its own inbox -
	 * see [CraftingTerminalHookMenu.requestIngredientSupply]). That request isn't instant for a
	 * network-sourced ingredient, so the *first* click transferring one still reports missing
	 * ingredients the same as any real shortfall - a second click succeeds once it's arrived.
	 */
	override fun registerRecipeTransferHandlers(registration: IRecipeTransferRegistration) {
		val helper = registration.transferHelper
		val basicInfo = helper.createBasicRecipeTransferInfo(
			CraftingTerminalHookMenu::class.java,
			GuiRegistry.CraftingTerminalHook,
			RecipeTypes.CRAFTING,
			CraftingTerminalHookMenu.GRID_SLOT_START,
			CraftingTerminalHookMenu.GRID_SLOT_COUNT,
			CraftingTerminalHookMenu.INVENTORY_SLOT_START,
			CraftingTerminalHookMenu.INVENTORY_SLOT_COUNT,
		)
		val delegate = helper.createUnregisteredRecipeTransferHandler(basicInfo)
		registration.addRecipeTransferHandler(
			object : IRecipeTransferHandler<CraftingTerminalHookMenu, RecipeHolder<CraftingRecipe>> {
				override fun getContainerClass() = CraftingTerminalHookMenu::class.java
				override fun getMenuType(): Optional<MenuType<CraftingTerminalHookMenu>> = Optional.of(GuiRegistry.CraftingTerminalHook)
				override fun getRecipeType() = RecipeTypes.CRAFTING

				override fun transferRecipe(
					container: CraftingTerminalHookMenu,
					recipe: RecipeHolder<CraftingRecipe>,
					recipeSlots: IRecipeSlotsView,
					player: Player,
					maxTransfer: Boolean,
					doTransfer: Boolean,
				): IRecipeTransferError? {
					if (doTransfer) {
						val resources = resourcesOf(recipe)
						if (resources.isNotEmpty()) container.requestIngredientSupply(resources)
					}
					return delegate.transferRecipe(container, recipe, recipeSlots, player, maxTransfer, doTransfer)
				}
			},
			RecipeTypes.CRAFTING,
		)
	}

	/** One representative [ItemResource] per ingredient of [recipe] - whichever the default transfer logic would itself reach for first, since that's what's actually missing when it can't find one. */
	private fun resourcesOf(recipe: RecipeHolder<CraftingRecipe>): List<ItemResource> =
		recipe.value.ingredients.mapNotNull { ingredient -> ingredient.items.firstOrNull()?.let { ItemResource.of(it) } }

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
		private const val HOVER_AREA_RADIUS = 8
	}
}
