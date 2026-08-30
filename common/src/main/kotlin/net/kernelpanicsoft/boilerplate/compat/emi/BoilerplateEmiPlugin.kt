package net.kernelpanicsoft.boilerplate.compat.emi

import com.mojang.blaze3d.systems.RenderSystem
import dev.emi.emi.api.EmiPlugin
import dev.emi.emi.api.EmiRegistry
import dev.emi.emi.api.recipe.EmiRecipe
import dev.emi.emi.api.recipe.VanillaEmiRecipeCategories
import dev.emi.emi.api.recipe.handler.EmiCraftContext
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler
import dev.emi.emi.api.stack.EmiIngredient
import dev.emi.emi.api.stack.EmiStack
import dev.emi.emi.api.stack.EmiStackInteraction
import dev.emi.emi.api.widget.Bounds
import dev.emi.emi.api.widget.SlotWidget
import dev.emi.emi.api.widget.Widget
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.boilerplate.pipe.gui.AbstractTerminalHookScreen
import net.kernelpanicsoft.boilerplate.pipe.gui.CraftingTerminalHookMenu
import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.kernelpanicsoft.boilerplate.util.itemStack
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.inventory.Slot
import java.util.*

/**
 * Unlike REI/JEI, EMI ships no *published* loader-agnostic api artifact under its own main
 * coordinates (`emi-fabric`/`emi-neoforge` each bundle their own copy of the same shared source,
 * confirmed by diffing their class lists - only two loader-specific ingredient-wrapper classes
 * differ) - but it does separately publish that shared source as its own intermediary-mapped
 * artifact, `dev.emi:emi-xplat-intermediary` (see `docs/design/m6-polish-parity.md`), which
 * Architectury Loom remaps per real platform exactly like Archie's own published modules. So this
 * plugin lives here in `common` too, same as [net.kernelpanicsoft.boilerplate.compat.rei.BoilerplateREIPlugin]/
 * [net.kernelpanicsoft.boilerplate.compat.jei.BoilerplateJEIPlugin] - no per-loader duplication
 * needed after all. Discovery still differs per loader, though: Fabric needs the `"emi"` entrypoint
 * declared in `fabric.mod.json` pointing at this class; NeoForge's own annotation scanning instead
 * looks for `@EmiEntrypoint` - deliberately kept on a separate marker subclass
 * (`neoforge/.../NeoForgeEmiEntrypoint`) rather than this one, since putting it directly here once
 * crashed Fabric's own unrelated entrypoint construction (`java.lang.annotation.AnnotationFormatError:
 * Attempt to create proxy for a non-annotation type: dev.emi.emi.api.EmiEntrypoint`, thrown from
 * Fabric Loader/Kotlin reflection eagerly reading *every* annotation on the class while
 * constructing the `"emi"` entrypoint, confirmed against a real crash log) - a genuine Loom/remap
 * quirk with this specific zero-member annotation type, not something either loader's own EMI
 * actually depends on this class *not* having.
 *
 * Same scope as [net.kernelpanicsoft.boilerplate.compat.rei.BoilerplateREIPlugin]/
 * [net.kernelpanicsoft.boilerplate.compat.jei.BoilerplateJEIPlugin] - only the Crafting Terminal's
 * manual 3x3 grid, the one part of either terminal screen that's both real-vanilla-[Slot]-backed
 * and matches a genuine registered vanilla `CraftingRecipe`. See those classes' KDoc for what's
 * deliberately out of scope.
 */
open class BoilerplateEmiPlugin : EmiPlugin {
	override fun register(registry: EmiRegistry) {
		registry.addRecipeHandler(
			GuiRegistry.CraftingTerminalHook,
			object : StandardRecipeHandler<CraftingTerminalHookMenu> {
				/**
				 * Deliberately excludes [CraftingTerminalHookMenu.gridSlots] - EMI's own default
				 * [craft] ([EmiRecipeFiller.clientFill], confirmed against its real source) always
				 * clears every crafting slot *first* (a real simulated `ClickType.THROW` click, ejecting
				 * whatever's there) before refilling from these sources, and its refill step explicitly
				 * refuses to treat a crafting slot as its own source (`if (slots.contains(input))
				 * continue`) - so a grid cell already correctly filled (by a prior
				 * [CraftingTerminalHookMenu.supplyIngredients] grant, say) would get thrown away here
				 * and could never be replaced from itself, only from elsewhere; if nothing spare exists
				 * outside the grid the fill just fails, but the original grid contents are already gone.
				 * EMI's own intended mechanism for "the grid already has some/all of this recipe" is
				 * [dev.emi.emi.registry.EmiRecipeFiller.batchesAlreadyPresent] instead, which reads
				 * [getCraftingSlots] directly - no need to *also* list it as an input source.
				 */
				override fun getInputSources(handler: CraftingTerminalHookMenu): List<Slot> = handler.outputSlots + handler.inventorySlots
				override fun getCraftingSlots(handler: CraftingTerminalHookMenu): List<Slot> = handler.gridSlots
				override fun supportsRecipe(recipe: EmiRecipe): Boolean = recipe.category == VanillaEmiRecipeCategories.CRAFTING

				/**
				 * `true` either when [StandardRecipeHandler]'s own default
				 * (`context.getInventory().canCraft(recipe)`) already says so (everything's already
				 * sitting in a real slot), or - when it doesn't - every ingredient [recipe] needs shows
				 * up in [CraftingTerminalHookMenu.results], the terminal's own already-synced "what's
				 * reachable" snapshot (see [AbstractTerminalHookMenu.results]'s own KDoc): a pure read
				 * of state the client already has, no request fired, no server round trip. This is
				 * deliberately just *optimistic*, not a guarantee - [results] is a total snapshot, not
				 * a live per-click reservation, so a second player draining the same source between
				 * this check and an actual click can still leave [craft] genuinely short. That's fine:
				 * this only has to get EMI's own fill button to look enabled and let a click reach
				 * [craft] at all (its own `performFill` never calls it otherwise, confirmed against its
				 * real source) - the real, mutating sufficiency check happens there instead. Must never
				 * fire [CraftingTerminalHookMenu.requestIngredientSupply] itself - this is evaluated
				 * every single frame a recipe view is open, not just on a click, so any real side effect
				 * here (pulling real stock from storage) would fire from the player merely
				 * *looking* at a recipe.
				 */
				override fun canCraft(recipe: EmiRecipe, context: EmiCraftContext<CraftingTerminalHookMenu>): Boolean {
					if (super.canCraft(recipe, context)) return true
					val reachable = context.screenHandler.results.mapTo(HashSet()) { it.resource }
					return targetsOf(recipe).values.all { it in reachable }
				}

				/**
				 * Only delegates to [StandardRecipeHandler]'s own default fill-then-craft logic
				 * ([super.craft]) once [super.canCraft] - re-checked here, freshly, not trusted from
				 * [canCraft]'s own outer optimistic answer - genuinely agrees everything's already
				 * available. That re-check is exactly the same [EmiPlayerInventory.canCraft] quantity-
				 * aware, all-or-nothing computation [super.craft]'s own [EmiRecipeFiller.getStacks] relies
				 * on internally, so trusting it here doesn't risk the mismatch [getInputSources]'s own
				 * KDoc describes - safe now that [getCraftingSlots] is excluded from [getInputSources].
				 * Still genuinely short (only [canCraft]'s own optimistic reachability check passed,
				 * not this stricter one) instead only asks the terminal to supply what's missing
				 * ([CraftingTerminalHookMenu.requestIngredientSupply], passing [EmiCraftContext.amount]
				 * straight through as the requested quantity - `1` for a plain fill, `Int.MAX_VALUE`
				 * for a shift-click ("fill with as much as possible"), or whatever specific count
				 * EMI's own BOM sidebar fill asks for when filling the grid for one particular step of
				 * a larger recipe tree, genuinely not always 1 or "as much as possible" - and reports
				 * failure for *this* click - a second click succeeds once that supply has actually
				 * landed and synced back. Fires on *every* call reaching this branch, no
				 * same-targets debounce: `craft` only ever runs once per real click (confirmed against
				 * `EmiRecipeFiller.performFill`'s own source - it isn't re-evaluated every frame the
				 * way `canCraft` is), so a debounce here doesn't guard against a flood, only against a
				 * second, later click for the *same* recipe - which needs to fire again just as much
				 * as the first, since [targetsOf] is stable and would otherwise compare equal forever.
				 */
				override fun craft(recipe: EmiRecipe, context: EmiCraftContext<CraftingTerminalHookMenu>): Boolean {
					if (super.canCraft(recipe, context)) return super.craft(recipe, context)
					val targets = targetsOf(recipe)
					if (targets.isNotEmpty()) context.screenHandler.requestIngredientSupply(targets, context.amount.toLong())
					return true
				}

				/**
				 * Overrides [StandardRecipeHandler]'s own default (`renderMissing`, confirmed against
				 * its real source) rather than layering on top of it - that default paints every missing
				 * ingredient the same flat red regardless of *why* it's missing, which this terminal can
				 * do better: matched against [CraftingTerminalHookMenu.results] (this terminal's own
				 * already-synced "what's reachable" snapshot - a request would actually fetch it) for
				 * orange, or [CraftingTerminalHookMenu.craftableResources] (a known pattern exists
				 * somewhere reachable) for blue, falling back to the original red only when neither
				 * applies - genuinely not obtainable right now. Rebuilds the same identity-keyed
				 * availability map [StandardRecipeHandler]'s own private `getAvailable` does (not
				 * reachable from here - a Java interface's `private static` method), since a
				 * [SlotWidget]'s own [EmiIngredient] is only reliably matched back to a specific recipe
				 * input by reference identity, not structural equality.
				 */
				override fun render(recipe: EmiRecipe, context: EmiCraftContext<CraftingTerminalHookMenu>, widgets: List<Widget>, draw: GuiGraphics) {
					val inputs = recipe.inputs
					val availability = context.inventory.getCraftAvailability(recipe)
					if (availability.size != inputs.size) return
					val available = IdentityHashMap<EmiIngredient, Boolean>()
					for (i in inputs.indices) available[inputs[i]] = availability[i]

					val menu = context.screenHandler
					RenderSystem.enableDepthTest()
					for (widget in widgets) {
						if (widget !is SlotWidget || widget.recipe != null) continue
						val stack = widget.stack
						if (stack.isEmpty || available[stack] != false) continue

						val resource = stack.emiStacks.firstOrNull { !it.isEmpty }?.itemStack?.takeUnless { it.isEmpty }?.let { ItemResource.of(it) }
						val color = when {
							resource != null && menu.results.any { it.resource == resource } -> COLOR_REQUESTABLE
							resource != null && menu.craftableResources.contains(resource) -> COLOR_CRAFTABLE
							else -> COLOR_MISSING
						}
						val bounds = widget.bounds
						draw.fill(bounds.x(), bounds.y(), bounds.x() + bounds.width(), bounds.y() + bounds.height(), color)
					}
				}
			},
		)

		registry.addExclusionArea(AbstractTerminalHookScreen::class.java) { screen: AbstractTerminalHookScreen<*>, consumer ->
			consumer.accept(Bounds(screen.screenLeft, screen.screenTop, screen.screenWidth, screen.screenHeight))
		}

		registry.addStackProvider(AbstractTerminalHookScreen::class.java) { screen: AbstractTerminalHookScreen<*>, _, _ ->
			screen.hoveredStack?.let { EmiStackInteraction(EmiStack.of(it.itemStack)) } ?: EmiStackInteraction.EMPTY
		}
	}

	/**
	 * One representative [ItemResource] per ingredient of [recipe], keyed by the exact grid cell it
	 * belongs in - [EmiCraftingRecipe]'s own `getInputs()` list is already stored row-major against
	 * a 3-wide grid (confirmed against its own `canFit`/`addWidgets`, which index it via `i % 3`/
	 * `i / 3` directly), so the list index *is* the grid index, no width conversion needed the way
	 * [net.kernelpanicsoft.boilerplate.compat.rei.BoilerplateREIPlugin]'s own REI-side mapping does.
	 */
	private fun targetsOf(recipe: EmiRecipe): Map<Int, ItemResource> =
		recipe.inputs.withIndex().mapNotNull { (index, ingredient) ->
			ingredient.emiStacks.firstOrNull { !it.isEmpty }
				?.itemStack?.takeUnless { it.isEmpty }
				?.let { index to ItemResource.of(it) }
		}.toMap()

	companion object {
		/** Genuinely not obtainable right now - not local, not reachable, no known pattern. Matches [StandardRecipeHandler]'s own default `renderMissing` color exactly. */
		private const val COLOR_MISSING = 0x44FF0000
		/** Not local, but present somewhere reachable - a request would actually fetch it. */
		private const val COLOR_REQUESTABLE = 0x44FFA500
		/** Not local and not reachable, but a known pattern could produce it somewhere reachable. */
		private const val COLOR_CRAFTABLE = 0x440080FF
	}
}
