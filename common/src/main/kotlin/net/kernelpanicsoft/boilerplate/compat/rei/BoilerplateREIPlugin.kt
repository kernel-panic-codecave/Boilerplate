package net.kernelpanicsoft.boilerplate.compat.rei

import dev.architectury.event.CompoundEventResult
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import it.unimi.dsi.fastutil.ints.IntSet
import me.shedaniel.math.Rectangle
import me.shedaniel.rei.api.client.gui.widgets.Slot
import me.shedaniel.rei.api.client.gui.widgets.Widget
import me.shedaniel.rei.api.client.plugins.REIClientPlugin
import me.shedaniel.rei.api.client.registry.screen.ExclusionZones
import me.shedaniel.rei.api.client.registry.screen.ScreenRegistry
import me.shedaniel.rei.api.client.registry.transfer.TransferHandler
import me.shedaniel.rei.api.client.registry.transfer.TransferHandlerRegistry
import me.shedaniel.rei.api.client.registry.transfer.simple.SimpleTransferHandler
import me.shedaniel.rei.api.common.category.CategoryIdentifier
import me.shedaniel.rei.api.common.display.Display
import me.shedaniel.rei.api.common.display.SimpleGridMenuDisplay
import me.shedaniel.rei.api.common.entry.InputIngredient
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes
import me.shedaniel.rei.api.common.transfer.info.stack.SlotAccessor
import me.shedaniel.rei.api.common.util.EntryStacks
import net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookScreen
import net.kernelpanicsoft.boilerplate.pipe.gui.CraftingTerminalHookMenu
import net.kernelpanicsoft.boilerplate.util.itemStack
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.item.ItemStack

/**
 * REI's own api artifact (`me.shedaniel:RoughlyEnoughItems-api`) is loader-agnostic, unlike
 * JEI/EMI's (see `compat/jei`/`compat/emi` in `fabric`/`neoforge`) - this plugin lives here in
 * `common` and is discovered per-loader by a one-line shim: Fabric's `rei_client` entrypoint in
 * `fabric.mod.json`, NeoForge's own
 * `META-INF/services/me.shedaniel.rei.api.client.plugins.REIClientPlugin` file. Neither entrypoint
 * mechanism ever loads this class unless the real REI mod is actually installed, so no separate
 * presence guard is needed - see `docs/design/m6-polish-parity.md`.
 *
 * Only the Crafting Terminal's manual 3x3 [net.kernelpanicsoft.boilerplate.pipe.hook.CraftingTerminalHookState.grid]
 * is covered so far - it's both real vanilla-[net.minecraft.world.inventory.Slot]-backed and
 * matches genuine registered vanilla `CraftingRecipe`s (see
 * [net.kernelpanicsoft.boilerplate.crafting.InstantCrafting.match]), so REI's own built-in
 * "Crafting" category can be reused wholesale - no custom [Display]/category needed. Pattern-driven
 * autocrafting (the Pattern Provider network, M4) has no relationship to any registered vanilla
 * recipe at all and would need a wholly new custom category - deferred, see the design doc.
 */
class BoilerplateREIPlugin : REIClientPlugin {
	/**
	 * Lets REI's own "transfer recipe" button fill [CraftingTerminalHookMenu.registerSlotHandlers]'s
	 * real `grid` slots from a shown vanilla crafting recipe, exactly as it already does for a real
	 * crafting table. [SimpleTransferHandler]'s own convenience `create(...)` factory hardcodes its
	 * "inventory" (source) side to the player's own inventory with no way to redirect it to other
	 * menu slots, so this implements the interface directly instead: [getInventorySlots] is this
	 * terminal's own `outputSlots` (the inbox) plus the player's own inventory, deliberately excluding
	 * [getInputSlots] (the grid itself) - JEI's own equivalent hard-rejects that exact overlap outright
	 * (see [net.kernelpanicsoft.boilerplate.compat.jei.BoilerplateJEIPlugin]'s own KDoc for the
	 * confirmed version of this), and EMI's own default fill unconditionally drops whatever's already
	 * in the grid before refilling it, unable to re-source from the very slot it just cleared (see
	 * [net.kernelpanicsoft.boilerplate.compat.emi.BoilerplateEmiPlugin]'s own KDoc) - silently
	 * destroying it if nothing spare exists elsewhere. Nothing here needs the grid listed as its own
	 * source either way: a cell already correctly filled just needs leaving alone, not re-supplied
	 * from itself. A click first asks the terminal to supply
	 * anything missing that it can reach (storage, the inbox - see
	 * [CraftingTerminalHookMenu.requestIngredientSupply]) before running the same fill
	 * [SimpleTransferHandler.handle]'s own default implementation already provides. That supply
	 * request isn't instant for a network-sourced ingredient, so the *first* click transferring one
	 * still reports "missing ingredients" the same as any real shortfall - a second click succeeds
	 * once it's arrived.
	 */
	override fun registerTransferHandlers(registry: TransferHandlerRegistry) {
		registry.register(object : SimpleTransferHandler {
			private var lastRequestedTargets: Pair<Map<Int, ItemResource>, Long>? = null

			override fun checkApplicable(context: TransferHandler.Context): TransferHandler.ApplicabilityResult =
				if (context.menu is CraftingTerminalHookMenu && CATEGORY == context.display.categoryIdentifier && context.containerScreen != null) {
					TransferHandler.ApplicabilityResult.createApplicable()
				} else {
					TransferHandler.ApplicabilityResult.createNotApplicable()
				}

			override fun getInputSlots(context: TransferHandler.Context): Iterable<SlotAccessor> {
				val menu = context.menu as? CraftingTerminalHookMenu ?: return emptyList()
				return menu.gridSlots.map { SlotAccessor.fromSlot(it) }
			}

			override fun getInventorySlots(context: TransferHandler.Context): Iterable<SlotAccessor> {
				val menu = context.menu as? CraftingTerminalHookMenu ?: return emptyList()
				return (menu.outputSlots + menu.inventorySlots).map { SlotAccessor.fromSlot(it) }
			}

			/**
			 * REI calls this both for a hover/preview check (`context.isActuallyCrafting() == false`,
			 * per this method's own interface KDoc) and a real click. Gated on
			 * [TransferHandler.Context.isActuallyCrafting] deliberately - firing
			 * [CraftingTerminalHookMenu.requestIngredientSupply] from a mere preview would pull real
			 * stock from storage just from the player hovering the "+"
			 * button, never having clicked anything. [targetsOf] is stable for a given [Display] (it
			 * just reads the recipe's own ingredients, not current stock), so comparing against
			 * [lastRequestedTargets] only guards against resending the same request on a rapid
			 * double-click, not against the preview case (already excluded above).
			 * [TransferHandler.Context.isStackedCrafting] is REI's own "shift-click fills as much as
			 * possible" signal - unlike EMI's own `EmiCraftContext.amount`, it's only ever a boolean,
			 * no specific quantity - so `true` maps to [Int.MAX_VALUE], relying on
			 * [CraftingTerminalHookMenu.supplyIngredients]'s own per-resource cap to bound it
			 * sensibly, same as [RequestIngredientSupplyPacket.amount]'s own KDoc describes.
			 */
			override fun handle(context: TransferHandler.Context): TransferHandler.Result {
				val menu = context.menu as? CraftingTerminalHookMenu
				val targets = targetsOf(context.display)
				val amount = if (context.isStackedCrafting) Int.MAX_VALUE.toLong() else 1L
				if (context.isActuallyCrafting && menu != null && targets.isNotEmpty() && (targets to amount) != lastRequestedTargets) {
					lastRequestedTargets = targets to amount
					menu.requestIngredientSupply(targets, amount)
				}
				return super.handle(context)
			}

			/**
			 * Overrides [SimpleTransferHandler]'s own default (a flat red on every missing input,
			 * confirmed against its real source) rather than layering on top of it, same treatment as
			 * [net.kernelpanicsoft.boilerplate.compat.emi.BoilerplateEmiPlugin]'s own `render` override:
			 * orange when the ingredient shows up in [CraftingTerminalHookMenu.results] (a request
			 * would actually fetch it), blue when it shows up in
			 * [CraftingTerminalHookMenu.craftableResources] instead (a known pattern exists somewhere
			 * reachable), red only when neither applies. Rebuilds the same "which widget is which
			 * missing input" walk [SimpleTransferHandler]'s own default `renderMissingInput` does -
			 * [missingIndices] are [InputIngredient.getDisplayIndex] values assigned in the same order
			 * as the [Slot.INPUT]-marked [widgets], not directly usable as a list index into anything
			 * else.
			 */
			override fun renderMissingInput(
				context: TransferHandler.Context,
				inputs: List<InputIngredient<ItemStack>>,
				missing: List<InputIngredient<ItemStack>>,
				missingIndices: IntSet,
				graphics: GuiGraphics,
				mouseX: Int,
				mouseY: Int,
				delta: Float,
				widgets: List<Widget>,
				bounds: Rectangle,
			) {
				val menu = context.menu as? CraftingTerminalHookMenu
				val resourceByIndex = missing.associate { ingredient ->
					ingredient.displayIndex to ingredient.get().firstOrNull { !it.isEmpty }?.let { ItemResource.of(it) }
				}
				var i = 0
				for (widget in widgets) {
					if (widget !is Slot || widget.noticeMark != Slot.INPUT) continue
					val index = i++
					if (!missingIndices.contains(index)) continue
					val resource = resourceByIndex[index]
					val color = when {
						menu != null && resource != null && menu.results.any { it.resource == resource } -> COLOR_REQUESTABLE
						menu != null && resource != null && menu.craftableResources.contains(resource) -> COLOR_CRAFTABLE
						else -> COLOR_MISSING
					}
					val innerBounds = widget.innerBounds
					graphics.fill(innerBounds.x, innerBounds.y, innerBounds.maxX, innerBounds.maxY, color)
				}
			}
		})
	}

	/**
	 * One representative [ItemResource] per input slot of [display], keyed by the exact grid cell
	 * it belongs in. [Display.getInputEntries] is only as wide as the recipe's own *functional*
	 * shape (a 2x2 recipe reports 4 entries, not 9) - [SimpleGridMenuDisplay.getWidth] (REI's own
	 * vanilla `DefaultCraftingDisplay` implements it) gives that real width back, converting a
	 * recipe-relative row-major index into a grid-relative one (`x = i % width; y = i / width;
	 * index = y * 3 + x`). This anchors every recipe to the grid's own top-left rather than
	 * REI's own centered display position - harmless, since vanilla's shaped-recipe matching tries
	 * every valid offset itself, top-left included, unlike
	 * [net.kernelpanicsoft.boilerplate.compat.emi.BoilerplateEmiPlugin]/
	 * [net.kernelpanicsoft.boilerplate.compat.jei.BoilerplateJEIPlugin]'s own EMI/JEI-reported
	 * indices, which are already grid-relative and need no such conversion.
	 */
	private fun targetsOf(display: Display): Map<Int, ItemResource> {
		val width = (display as? SimpleGridMenuDisplay)?.width ?: GRID_WIDTH
		return display.inputEntries.withIndex().mapNotNull { (i, ingredient) ->
			ingredient.firstOrNull { it.type == VanillaEntryTypes.ITEM && !it.isEmpty }
				?.let { (i / width * GRID_WIDTH + i % width) to ItemResource.of(it.castValue<ItemStack>()) }
		}.toMap()
	}

	/** Keeps REI's own item panel off every terminal screen's real occupied rectangle - fully custom Compose content (the Store search grid, sidebar, tabs), not real vanilla [net.minecraft.world.inventory.Slot]s REI could otherwise infer bounds from on its own. */
	override fun registerExclusionZones(zones: ExclusionZones) {
		zones.register(AbstractTerminalHookScreen::class.java) { screen: AbstractTerminalHookScreen<*> ->
			listOf(Rectangle(screen.screenLeft, screen.screenTop, screen.screenWidth, screen.screenHeight))
		}
	}

	/** Surfaces [AbstractTerminalHookScreen.hoveredStack] - the Store list's own non-slot hover tracking - to REI's "view recipes"/"view usages" hover lookup, which otherwise only ever sees real vanilla [net.minecraft.world.inventory.Slot]s. */
	override fun registerScreens(registry: ScreenRegistry) {
		registry.registerFocusedStack { screen, _ ->
			val terminal = screen as? AbstractTerminalHookScreen<*> ?: return@registerFocusedStack CompoundEventResult.pass()
			val stack = terminal.hoveredStack ?: return@registerFocusedStack CompoundEventResult.pass()
			CompoundEventResult.interruptTrue(EntryStacks.of(stack.itemStack))
		}
	}

	companion object {
		private val CATEGORY = CategoryIdentifier.of<Display>("minecraft", "plugins/crafting")
		private const val GRID_WIDTH = 3

		/** Genuinely not obtainable right now - not local, not reachable, no known pattern. Matches [SimpleTransferHandler]'s own default `renderMissingInput` color exactly. */
		private const val COLOR_MISSING = 0x40FF0000
		/** Not local, but present somewhere reachable - a request would actually fetch it. */
		private const val COLOR_REQUESTABLE = 0x40FFA500
		/** Not local and not reachable, but a known pattern could produce it somewhere reachable. */
		private const val COLOR_CRAFTABLE = 0x400080FF
	}
}
