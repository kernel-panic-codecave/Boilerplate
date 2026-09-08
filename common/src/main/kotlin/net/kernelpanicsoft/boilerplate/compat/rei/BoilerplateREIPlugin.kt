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
import net.kernelpanicsoft.boilerplate.compat.ViewerResourceStacks
import me.shedaniel.rei.api.common.entry.EntryStack
import dev.architectury.fluid.FluidStack
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.ResourceComponent

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
@Suppress("UnstableApiUsage")
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
			 * stock from storage just from the player hovering the "+" button, never having clicked
			 * anything. No same-targets debounce needed beyond that: `handle` only ever runs once per
			 * real click, not re-evaluated every frame the way the preview case is (already excluded
			 * above), so nothing here floods - and [targetsOf] is stable for a given [Display], so a
			 * debounce would incorrectly suppress a genuine second click for the *same* recipe too.
			 * [TransferHandler.Context.isStackedCrafting] is REI's own "shift-click fills as much as
			 * possible" signal - unlike EMI's own `EmiCraftContext.amount`, it's only ever a boolean,
			 * no specific quantity - so `true` maps to [Int.MAX_VALUE], relying on
			 * [CraftingTerminalHookMenu.supplyIngredients]'s own per-resource cap to bound it
			 * sensibly, same as [RequestIngredientSupplyPacket.amount]'s own KDoc describes.
			 *
			 * The preview call also decides whether the "+" button is even clickable at all -
			 * `AutoCraftingEvaluator.evaluateAutoCrafting` (confirmed against its real source) ties the
			 * button's own `setEnabled` directly to this call's `isSuccessful()`, evaluated with
			 * `isActuallyCrafting = false`. [SimpleTransferHandler]'s own default only considers
			 * current local stock (`getInventorySlots`), with no concept of "the terminal could go get
			 * this" - so without an override here, the button stays permanently disabled the moment
			 * any ingredient needs to come from the network, exactly the [BoilerplateEmiPlugin.canCraft]
			 * problem again. Optimistically reports success when every target is already local or
			 * reachable via [CraftingTerminalHookMenu.results] - deliberately *not* counting
			 * [CraftingTerminalHookMenu.craftableResources] here, since a pattern existing doesn't mean
			 * [CraftingTerminalHookMenu.requestIngredientSupply] can actually fulfill it (that's a
			 * separate, deferred feature - see `docs/design/m6-polish-parity.md`) - manually attaching
			 * the same tri-color renderer so the highlight still shows once the button reports success.
			 *
			 * On a real click, only takes over from [super.handle] when *something* is still
			 * genuinely not local yet - [requestIngredientSupply] only ever tops up what's missing,
			 * it never itself moves anything into the grid, so falling through to [super.handle]
			 * unconditionally-skipped would mean a fully-local recipe (nothing to fetch at all) never
			 * actually gets filled via this button at all. When at least one target still needs
			 * fetching, reports success without calling [super.handle] instead of letting it run
			 * against a still-incomplete grid - deliberately closes the recipe view (REI's own click
			 * handler does this for any successful result) rather than leaving it open pretending the
			 * fill already happened; a second click once the supply has landed reaches the
			 * obtainability check below as `false` and falls through to the real fill normally.
			 *
			 * Every obtainability check here goes through [allResourcesOf] rather than [targetsOf]'s
			 * own single-representative-per-cell map - a tag-backed ingredient (any plank color, say)
			 * has several possible alternatives per cell, and checking only one (confirmed the hard
			 * way, first found on JEI: whichever alternative happens to be first) meant a cell looked
			 * "missing" even while a *different* alternative the player already had sat right there for
			 * the same cell.
			 */
			override fun handle(context: TransferHandler.Context): TransferHandler.Result {
				val menu = context.menu as? CraftingTerminalHookMenu
				val targets = targetsOf(context.display)
				val allResources = allResourcesOf(context.display)

				if (!context.isActuallyCrafting) {
					if (menu != null && allResources.isNotEmpty()) {
						val reachable = menu.results.mapTo(HashSet()) { it.resource }
						if (allResources.values.all { alts -> alts.any { it in reachable || isLocallyAvailable(menu, it) } }) {
							return TransferHandler.Result.createSuccessful()
								.renderer { graphics, _, _, _, widgets, _, display -> renderTargets(menu, display, graphics, widgets) }
						}
					}
					return super.handle(context)
				}

				val stillMissing = menu != null && allResources.isNotEmpty() && !allResources.values.all { alts -> alts.any { isLocallyAvailable(menu, it) } }
				if (menu != null && targets.isNotEmpty() && stillMissing) {
					val amount = if (context.isStackedCrafting) Int.MAX_VALUE.toLong() else 1L
					menu.requestIngredientSupply(targets, amount)
					return TransferHandler.Result.createSuccessful()
				}
				return super.handle(context)
			}

			/**
			 * Overrides [SimpleTransferHandler]'s own default (a flat red on every missing input,
			 * confirmed against its real source) rather than layering on top of it, same treatment as
			 * [net.kernelpanicsoft.boilerplate.compat.emi.BoilerplateEmiPlugin]'s own `render` override -
			 * see [colorFor]. Rebuilds the same "which widget is which missing input" walk
			 * [SimpleTransferHandler]'s own default `renderMissingInput` does - [missingIndices] are
			 * [InputIngredient.getDisplayIndex] values assigned in the same order as the
			 * [Slot.INPUT]-marked [widgets], not directly usable as a list index into anything else.
			 * Only reached when [handle]'s own optimistic success path above didn't already take over -
			 * i.e. something here is genuinely still short even accounting for what's reachable.
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
				val resourcesByIndex = missing.associate { ingredient ->
					ingredient.displayIndex to ingredient.get().filter { !it.isEmpty }.map { ItemResource.of(it) }
				}
				var i = 0
				for (widget in widgets) {
					if (widget !is Slot || widget.noticeMark != Slot.INPUT) continue
					val index = i++
					if (!missingIndices.contains(index)) continue
					val resources = resourcesByIndex[index]?.takeIf { it.isNotEmpty() } ?: continue
					val innerBounds = widget.innerBounds
					graphics.fill(innerBounds.x, innerBounds.y, innerBounds.maxX, innerBounds.maxY, colorForAny(menu, resources))
				}
			}

			/**
			 * Same coloring [renderMissingInput] does, but driven straight from [display]/[widgets]
			 * alone (no [missing]/[missingIndices] to lean on, since this only ever runs from
			 * [handle]'s own optimistic-success [TransferHandler.Result.renderer] - REI never computes
			 * those for a successful result) - a cell not yet locally satisfied by *any* of its own
			 * possible alternatives gets colored via [colorForAny], same as the genuinely-missing case.
			 */
			private fun renderTargets(menu: CraftingTerminalHookMenu, display: Display, graphics: GuiGraphics, widgets: List<Widget>) {
				val inputEntries = display.inputEntries
				var i = 0
				for (widget in widgets) {
					if (widget !is Slot || widget.noticeMark != Slot.INPUT) continue
					val index = i++
					val resources = inputEntries.getOrNull(index)
						?.mapNotNull { entry -> if (entry.type == VanillaEntryTypes.ITEM && !entry.isEmpty) ItemResource.of(entry.castValue<ItemStack>()) else null }
						?.takeIf { it.isNotEmpty() }
						?: continue
					if (resources.any { isLocallyAvailable(menu, it) }) continue
					val innerBounds = widget.innerBounds
					graphics.fill(innerBounds.x, innerBounds.y, innerBounds.maxX, innerBounds.maxY, colorForAny(menu, resources))
				}
			}

			/**
			 * Same as [colorFor] on [net.kernelpanicsoft.boilerplate.compat.emi.BoilerplateEmiPlugin],
			 * but over every possible alternative [resources] a cell could be satisfied by instead of
			 * one fixed resource - a tag-backed ingredient (any plank color, say) has several, and
			 * checking only [targetsOf]'s own single representative meant a cell looked "missing"
			 * even while a *different* alternative the player already had sat right there for the
			 * same cell.
			 */
			private fun colorForAny(menu: CraftingTerminalHookMenu?, resources: List<ItemResource>): Int = when {
				menu != null && resources.any { r -> menu.results.any { it.resource == r } } -> COLOR_REQUESTABLE
				menu != null && resources.any { menu.craftableResources.contains(it) } -> COLOR_CRAFTABLE
				else -> COLOR_MISSING
			}

			/** Whether [resource] already sits in a real slot [menu] can see - the grid included, since something already sitting there isn't "missing" regardless of whether it counts as a fill *source* (see [getInventorySlots]'s own KDoc). */
			private fun isLocallyAvailable(menu: CraftingTerminalHookMenu, resource: ItemResource): Boolean =
				(menu.outputSlots + menu.gridSlots + menu.inventorySlots).any { slot ->
					val stack = slot.item
					!stack.isEmpty && ItemResource.of(stack) == resource
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

	/**
	 * Every possible [ItemResource] alternative each input slot of [display] could be satisfied by,
	 * keyed the same grid-relative way [targetsOf] is - a tag-backed ingredient (any plank color,
	 * say) has several, not just [targetsOf]'s own single representative. Used for "is this cell
	 * obtainable at all" checks, never for [CraftingTerminalHookMenu.requestIngredientSupply] itself
	 * (which still needs [targetsOf]'s one concrete resource per cell to actually request).
	 */
	private fun allResourcesOf(display: Display): Map<Int, List<ItemResource>> {
		val width = (display as? SimpleGridMenuDisplay)?.width ?: GRID_WIDTH
		return display.inputEntries.withIndex().mapNotNull { (i, ingredient) ->
			val resources = ingredient.mapNotNull { entry ->
				if (entry.type == VanillaEntryTypes.ITEM && !entry.isEmpty) ItemResource.of(entry.castValue<ItemStack>()) else null
			}
			if (resources.isEmpty()) null else (i / width * GRID_WIDTH + i % width) to resources
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
			val entry = ReiResourceStacks.of(stack.resource as ResourceComponent, stack.amount)
				?: return@registerFocusedStack CompoundEventResult.pass()
			CompoundEventResult.interruptTrue(entry)
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

/**
 * How each resource kind is shown to REI - see [ViewerResourceStacks], and the EMI plugin's own
 * twin for why there is one of these per viewer rather than one shared bridge.
 *
 * REI's fluid entries are built from Architectury's own cross-loader `FluidStack`, so unlike JEI's
 * this needs no platform seam.
 */
val ReiResourceStacks = ViewerResourceStacks<EntryStack<*>>().apply {
	register("item") { resource, amount ->
		(resource as? ItemResource)?.takeIf { !it.isBlank }?.let { EntryStacks.of(it.toStack(amount.toInt().coerceAtLeast(1))) }
	}
	register("fluid") { resource, amount ->
		(resource as? FluidResource)?.takeIf { !it.isBlank }?.let { EntryStacks.of(FluidStack.create(it.type, amount)) }
	}
}
