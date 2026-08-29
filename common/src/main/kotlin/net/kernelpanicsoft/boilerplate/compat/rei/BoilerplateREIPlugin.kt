package net.kernelpanicsoft.boilerplate.compat.rei

import dev.architectury.event.CompoundEventResult
import me.shedaniel.math.Rectangle
import me.shedaniel.rei.api.client.plugins.REIClientPlugin
import me.shedaniel.rei.api.client.registry.screen.ExclusionZones
import me.shedaniel.rei.api.client.registry.screen.ScreenRegistry
import me.shedaniel.rei.api.client.registry.transfer.TransferHandlerRegistry
import me.shedaniel.rei.api.client.registry.transfer.simple.SimpleTransferHandler
import me.shedaniel.rei.api.common.category.CategoryIdentifier
import me.shedaniel.rei.api.common.display.Display
import me.shedaniel.rei.api.common.util.EntryStacks
import net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookScreen
import net.kernelpanicsoft.boilerplate.pipe.gui.CraftingTerminalHookMenu
import net.kernelpanicsoft.boilerplate.util.itemStack

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
	/** Lets REI's own "transfer recipe" button fill [CraftingTerminalHookMenu.registerSlotHandlers]'s real `grid` slots from a shown vanilla crafting recipe, exactly as it already does for a real crafting table. */
	override fun registerTransferHandlers(registry: TransferHandlerRegistry) {
		registry.register(
			SimpleTransferHandler.create(
				CraftingTerminalHookMenu::class.java,
				CategoryIdentifier.of<Display>("minecraft", "plugins/crafting"),
				SimpleTransferHandler.IntRange(GRID_SLOT_START, GRID_SLOT_START + GRID_SLOT_COUNT),
			)
		)
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
		private const val GRID_SLOT_START = 9
		private const val GRID_SLOT_COUNT = 9
	}
}
