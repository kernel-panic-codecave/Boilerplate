package net.kernelpanicsoft.boilerplate.compat.jei

import earth.terrarium.common_storage_lib.resources.item.ItemResource
import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.constants.RecipeTypes
import mezz.jei.api.gui.builder.IClickableIngredientFactory
import mezz.jei.api.gui.builder.ITooltipBuilder
import mezz.jei.api.gui.handlers.IGuiContainerHandler
import mezz.jei.api.gui.ingredient.IRecipeSlotView
import mezz.jei.api.gui.ingredient.IRecipeSlotsView
import mezz.jei.api.recipe.RecipeIngredientRole
import mezz.jei.api.recipe.RecipeType
import mezz.jei.api.recipe.transfer.IRecipeTransferError
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper
import mezz.jei.api.recipe.transfer.IRecipeTransferInfo
import mezz.jei.api.registration.IGuiHandlerRegistration
import mezz.jei.api.registration.IRecipeTransferRegistration
import mezz.jei.api.runtime.IClickableIngredient
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookScreen
import net.kernelpanicsoft.boilerplate.pipe.gui.CraftingTerminalHookMenu
import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.kernelpanicsoft.boilerplate.util.itemStack
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.Rect2i
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
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
	 * default logic ([transferInfo]), untouched, wrapped so a click first asks the terminal to supply
	 * anything missing that it can reach (storage, its own inbox - see
	 * [CraftingTerminalHookMenu.requestIngredientSupply]).
	 *
	 * [transferInfo] is a hand-written [IRecipeTransferInfo] rather than the simpler
	 * [IRecipeTransferHandlerHelper.createBasicRecipeTransferInfo] convenience (which only accepts
	 * one contiguous "inventory" range) precisely so its [IRecipeTransferInfo.getInventorySlots] can
	 * be `outputSlots + inventorySlots` - the inbox and the player's own inventory, deliberately
	 * excluding [CraftingTerminalHookMenu.gridSlots] even though that leaves output/grid/inventory
	 * non-contiguous. [RecipeTransferUtil.validateSlots][mezz.jei.common.transfer.RecipeTransferUtil.validateSlots]
	 * (confirmed against its real source) hard-rejects any overlap between "inventory" and "crafting"
	 * slots outright - the earlier version's inventory span ran from
	 * [CraftingTerminalHookMenu.OUTPUT_SLOT_START] through the end of the player's own inventory,
	 * which included the grid range too, so every transfer here was silently failing validation
	 * before ever reaching a real move. [BasicRecipeTransferHandler][mezz.jei.library.transfer.BasicRecipeTransferHandler]'s
	 * own `getInventoryState` already folds a crafting slot's *existing* contents into the available
	 * pool on its own, so nothing here needs the grid listed as a source to recognize "already
	 * correctly placed" - that's handled without our help.
	 *
	 * That supply request isn't instant for a network-sourced ingredient, so the *first* click
	 * transferring one still reports missing ingredients the same as any real shortfall - a second
	 * click succeeds once it's arrived.
	 */
	override fun registerRecipeTransferHandlers(registration: IRecipeTransferRegistration) {
		val helper = registration.transferHelper
		val transferInfo = object : IRecipeTransferInfo<CraftingTerminalHookMenu, RecipeHolder<CraftingRecipe>> {
			override fun getContainerClass() = CraftingTerminalHookMenu::class.java
			override fun getMenuType(): Optional<MenuType<CraftingTerminalHookMenu>> = Optional.of(GuiRegistry.CraftingTerminalHook)
			override fun getRecipeType(): RecipeType<RecipeHolder<CraftingRecipe>> = RecipeTypes.CRAFTING
			override fun canHandle(container: CraftingTerminalHookMenu, recipe: RecipeHolder<CraftingRecipe>) = true
			override fun getRecipeSlots(container: CraftingTerminalHookMenu, recipe: RecipeHolder<CraftingRecipe>): List<Slot> = container.gridSlots
			override fun getInventorySlots(container: CraftingTerminalHookMenu, recipe: RecipeHolder<CraftingRecipe>): List<Slot> =
				container.outputSlots + container.inventorySlots
		}
		val delegate = helper.createUnregisteredRecipeTransferHandler(transferInfo)
		registration.addRecipeTransferHandler(
			object : IRecipeTransferHandler<CraftingTerminalHookMenu, RecipeHolder<CraftingRecipe>> {
				override fun getContainerClass() = CraftingTerminalHookMenu::class.java
				override fun getMenuType(): Optional<MenuType<CraftingTerminalHookMenu>> = Optional.of(GuiRegistry.CraftingTerminalHook)
				override fun getRecipeType() = RecipeTypes.CRAFTING

				/**
				 * JEI calls this with `doTransfer = false` first as a dry-run check (to decide the
				 * transfer button's own enabled/tooltip state), then again with `doTransfer = true` on
				 * an actual click. Gated on [doTransfer] deliberately - firing
				 * [CraftingTerminalHookMenu.requestIngredientSupply] from the dry run would pull real
				 * stock from storage just from JEI re-evaluating the
				 * button's own state, never an actual click. Unlike
				 * [net.kernelpanicsoft.boilerplate.compat.emi.BoilerplateEmiPlugin]'s own `canCraft`/
				 * `craft` pair, [delegate] doesn't trust this dry run's answer as vouched-for - both
				 * calls reach the *same* method, which independently reverifies sufficiency each time -
				 * so gating here doesn't risk starving the real commit the way an honest `canCraft`
				 * would there. No same-targets debounce needed beyond the [doTransfer] gate itself:
				 * this only ever runs once per real click with `doTransfer = true`, not re-evaluated
				 * every frame the way the dry run is (already excluded above), so nothing floods - and
				 * [targetsOf] is stable for a given [recipe], so a debounce would incorrectly suppress
				 * a genuine second click for the *same* recipe too. [maxTransfer] is JEI's own
				 * "shift-click fills as much as possible" signal - unlike EMI's own
				 * `EmiCraftContext.amount`, it's only ever a boolean, no specific quantity - so `true`
				 * maps to [Int.MAX_VALUE], relying on [CraftingTerminalHookMenu.supplyIngredients]'s
				 * own per-resource cap to bound it sensibly, same as
				 * [RequestIngredientSupplyPacket.amount]'s own KDoc describes.
				 *
				 * When [delegate] reports an error, substitutes its own flat-red highlight for
				 * [MissingIngredientError]'s tri-color one whenever the error is genuinely about missing
				 * ingredients - [delegate] exposes no way to tell *which* [IRecipeTransferError] it
				 * returned, so this recomputes "which input slots aren't satisfied yet" itself instead
				 * of trying to unpack that opaque result.
				 */
				override fun transferRecipe(
					container: CraftingTerminalHookMenu,
					recipe: RecipeHolder<CraftingRecipe>,
					recipeSlots: IRecipeSlotsView,
					player: Player,
					maxTransfer: Boolean,
					doTransfer: Boolean,
				): IRecipeTransferError? {
					val targets = targetsOf(helper, recipe)
					if (doTransfer && targets.isNotEmpty()) {
						val amount = if (maxTransfer) Int.MAX_VALUE.toLong() else 1L
						container.requestIngredientSupply(targets, amount)
					}
					val error = delegate.transferRecipe(container, recipe, recipeSlots, player, maxTransfer, doTransfer) ?: return null
					val missing = missingInputSlots(container, recipeSlots)
					return if (missing.isEmpty()) error else MissingIngredientError(container, missing)
				}
			},
			RecipeTypes.CRAFTING,
		)
	}

	/** Every [RecipeIngredientRole.INPUT] slot of [recipeSlots] not already satisfiable from [menu]'s own accessible slots - the grid included, since something already sitting there isn't "missing" regardless of whether it counts as a fill *source*. */
	private fun missingInputSlots(menu: CraftingTerminalHookMenu, recipeSlots: IRecipeSlotsView): List<IRecipeSlotView> =
		recipeSlots.getSlotViews(RecipeIngredientRole.INPUT).filter { view ->
			val resource = view.displayedItemStack.map { ItemResource.of(it) }.orElse(null)
			resource != null && !isLocallyAvailable(menu, resource)
		}

	private fun isLocallyAvailable(menu: CraftingTerminalHookMenu, resource: ItemResource): Boolean =
		(menu.outputSlots + menu.gridSlots + menu.inventorySlots).any { slot ->
			val stack = slot.item
			!stack.isEmpty && ItemResource.of(stack) == resource
		}

	/**
	 * One representative [ItemResource] per ingredient of [recipe], keyed by the exact grid cell it
	 * belongs in - [IRecipeTransferHandlerHelper.getGuiSlotIndexToIngredientMap] already reports
	 * that "indexed by the original gui slots" mapping directly (accounting for JEI's own
	 * smaller-recipe centering internally), so no positional math is needed here at all, unlike
	 * [net.kernelpanicsoft.boilerplate.compat.rei.BoilerplateREIPlugin]'s own REI-side mapping.
	 */
	private fun targetsOf(helper: IRecipeTransferHandlerHelper, recipe: RecipeHolder<CraftingRecipe>): Map<Int, ItemResource> =
		helper.getGuiSlotIndexToIngredientMap(recipe).mapNotNull { (slot, ingredient) ->
			ingredient.items.firstOrNull()?.let { slot to ItemResource.of(it) }
		}.toMap()

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

/** Genuinely not obtainable right now - not local, not reachable, no known pattern. Matches [mezz.jei.library.transfer.RecipeTransferErrorMissingSlots]'s own default highlight color exactly. */
private const val JEI_COLOR_MISSING = 0x66FF0000
/** Not local, but present somewhere reachable - a request would actually fetch it. */
private const val JEI_COLOR_REQUESTABLE = 0x66FFA500
/** Not local and not reachable, but a known pattern could produce it somewhere reachable. */
private const val JEI_COLOR_CRAFTABLE = 0x660080FF

/**
 * Same shape as JEI's own internal `RecipeTransferErrorMissingSlots` (not part of the published api
 * jar, confirmed against its real source - only [IRecipeTransferError]/[IRecipeSlotView] themselves
 * are), but colors each [missing] slot by what it'd actually take to get it instead of a flat red -
 * see [net.kernelpanicsoft.boilerplate.compat.emi.BoilerplateEmiPlugin]'s own `render` override for
 * the same treatment there.
 */
private class MissingIngredientError(private val menu: CraftingTerminalHookMenu, private val missing: List<IRecipeSlotView>) : IRecipeTransferError {
	override fun getType(): IRecipeTransferError.Type = IRecipeTransferError.Type.USER_FACING

	override fun showError(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, recipeSlotsView: IRecipeSlotsView, recipeX: Int, recipeY: Int) {
		val poseStack = guiGraphics.pose()
		poseStack.pushPose()
		poseStack.translate(recipeX.toFloat(), recipeY.toFloat(), 0f)
		for (view in missing) {
			val resource = view.displayedItemStack.map { ItemResource.of(it) }.orElse(null)
			val color = when {
				resource != null && menu.results.any { it.resource == resource } -> JEI_COLOR_REQUESTABLE
				resource != null && menu.craftableResources.contains(resource) -> JEI_COLOR_CRAFTABLE
				else -> JEI_COLOR_MISSING
			}
			view.drawHighlight(guiGraphics, color)
		}
		poseStack.popPose()
	}

	override fun getMissingCountHint(): Int = missing.size

	override fun getTooltip(tooltip: ITooltipBuilder) {
		tooltip.add(Component.translatable("jei.tooltip.error.recipe.transfer.missing"))
	}
}
