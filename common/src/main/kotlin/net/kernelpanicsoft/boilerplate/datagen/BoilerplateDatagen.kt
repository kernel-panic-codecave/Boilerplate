package net.kernelpanicsoft.boilerplate.datagen

import net.kernelpanicsoft.archie.data.ADataGenerator
import net.kernelpanicsoft.archie.data.ADatagenEventObject
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.registry.BlockRegistry
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.registry.TagsRegistry
import net.minecraft.data.loot.LootTableProvider
import net.minecraft.data.loot.LootTableSubProvider
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.data.recipes.RecipeCategory
import net.minecraft.world.item.Item
import net.kernelpanicsoft.archie.util.rem
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets

/**
 * The filter cards a reset recipe is generated for - every card kind that can actually be
 * configured. [ItemRegistry.CombinedFilterCard] included: dropping a child card into its grid
 * configures it (see [net.kernelpanicsoft.boilerplate.pipe.hook.filter.FilterCardTarget]), so it
 * needs the same way back.
 */
private val RESETTABLE_FILTER_CARDS: List<Item> by lazy {
	listOf(
		ItemRegistry.ItemFilterCard,
		ItemRegistry.FluidFilterCard,
		ItemRegistry.ModFilterCard,
		ItemRegistry.TagFilterCard,
		ItemRegistry.ColorFilterCard,
		ItemRegistry.RegexFilterCard,
		ItemRegistry.CombinedFilterCard,
	)
}

/**
 * Registers Boilerplate's datagen providers. Only ever touched from behind
 * [net.kernelpanicsoft.archie.data.platform.ADataGeneratorPlatform.isDataGen] - see
 * [Boilerplate.init] - so `archie-datagen-common`, a dev-only dependency absent from the
 * production runtime classpath, is never resolved outside of a `runDatagen` launch.
 */
internal object BoilerplateDatagen : ADatagenEventObject(Boilerplate.MOD) {
	override fun ADataGenerator.handler() {
		client {
			blockStates {
				boilerplateBlockStates()
			}
			languages {
				addBlock("Pipe") { BlockRegistry.Pipe }
				addBlock("Glass Pipe") { BlockRegistry.GlassPipe }
				addBlock("Warehouse Controller") { BlockRegistry.WarehouseController }
				addBlock("Gantry Rail") { BlockRegistry.GantryRail }
				addBlock("General Rack") { BlockRegistry.GeneralRack }
				addBlock("Bulk Rack") { BlockRegistry.BulkRack }
				addBlock("Unstackable Rack") { BlockRegistry.UnstackableRack }
				addBlock("Fluid Tank") { BlockRegistry.FluidTank }

				// Hook items override their descriptionId to `hook.<type id>` - adding them here
				// emits those keys, which is also what the segment menus' own titles read.
				addItem("Extraction Hook") { ItemRegistry.ExtractionHook }
				addItem("Filter Hook") { ItemRegistry.FilterHook }
				addItem("Provider Hook") { ItemRegistry.ProviderHook }
				addItem("Sync Hook") { ItemRegistry.SyncHook }
				addItem("Requester Hook") { ItemRegistry.RequesterHook }
				addItem("Terminal Hook") { ItemRegistry.TerminalHook }
				addItem("Interface Hook") { ItemRegistry.InterfaceHook }
				addItem("Crafting Terminal Hook") { ItemRegistry.CraftingTerminalHook }
				addItem("Pattern Provider Hook") { ItemRegistry.PatternProviderHook }
				addItem("Pattern Terminal Hook") { ItemRegistry.PatternTerminalHook }
				addItem("Adapter Hook") { ItemRegistry.AdapterHook }

				// Same convention - EncasementItem answers as `encasement.<type id>`.
				addItem("Crafting Buffer") { ItemRegistry.CraftingBufferEncasement }

				// FilterCardItem answers as `filter.<condition type id>`.
				addItem("Item Condition Card") { ItemRegistry.ItemFilterCard }
				addItem("Fluid Condition Card") { ItemRegistry.FluidFilterCard }
				addItem("Mod Condition Card") { ItemRegistry.ModFilterCard }
				addItem("Tag Condition Card") { ItemRegistry.TagFilterCard }
				addItem("Color Condition Card") { ItemRegistry.ColorFilterCard }
				addItem("Regex Condition Card") { ItemRegistry.RegexFilterCard }
				addItem("Combined Condition Card") { ItemRegistry.CombinedFilterCard }

				addItem("Pattern") { ItemRegistry.Pattern }
				add("${ItemRegistry.Pattern.descriptionId}.blank", "Blank Pattern")
				add("${ItemRegistry.Pattern.descriptionId}.kind.crafting", "Crafting Pattern")
				add("${ItemRegistry.Pattern.descriptionId}.kind.processing", "Processing Pattern")

				addItem("Warehouse Wand") { ItemRegistry.WarehouseWand }
				addItem("Brass Wrench") { ItemRegistry.BrassWrench }
				addItem("Diamond Wrench") { ItemRegistry.DiamondWrench }
			}
		}
		common {
			// Crafting a configured filter card on its own hands back a fresh one. That is the only
			// way back across FilterCardState.configured, and it needs to exist: a configured card in
			// a StockingRow cell means "everything I accept", so without a reset there is no way to
			// turn one back into an ordinary stockable item (or to clear a card and start over).
			// Vanilla shapeless crafting builds its result from the item alone, discarding the input
			// stack's components, which is exactly the reset - no custom recipe type needed.
			recipes { output ->
				for (card in RESETTABLE_FILTER_CARDS) {
					shapeless {
						category = RecipeCategory.MISC
						result = card
						ingredients { 1 of card }
						group = "boilerplate_filter_card_reset"
					}.unlockedBy(card).save(output, Boilerplate.MOD % "reset_${BuiltInRegistries.ITEM.getKey(card.asItem()).path}")
				}
			}
			blockTags { _ ->
				TagsRegistry.Blocks.MINEABLE_WRENCH += BlockRegistry.Pipe
				TagsRegistry.Blocks.MINEABLE_WRENCH += BlockRegistry.GlassPipe
				TagsRegistry.Blocks.MINEABLE_WRENCH += BlockRegistry.Multipart
				TagsRegistry.Blocks.MINEABLE_WRENCH += BlockRegistry.WarehouseController
				TagsRegistry.Blocks.MINEABLE_WRENCH += BlockRegistry.GantryRail
				TagsRegistry.Blocks.MINEABLE_WRENCH += BlockRegistry.GeneralRack
				TagsRegistry.Blocks.MINEABLE_WRENCH += BlockRegistry.BulkRack
				TagsRegistry.Blocks.MINEABLE_WRENCH += BlockRegistry.UnstackableRack
				TagsRegistry.Blocks.MINEABLE_WRENCH += BlockRegistry.FluidTank
			}
			itemTags { _ ->
				TagsRegistry.Items.TOOLS_WRENCH += ItemRegistry.BrassWrench
				TagsRegistry.Items.TOOLS_WRENCH += ItemRegistry.DiamondWrench
			}
			addProvider(isServer) { output, registries ->
				val tables = boilerplateLootTables()
				LootTableProvider(
					output,
					tables.keys.toSet(),
					listOf(
						LootTableProvider.SubProviderEntry(
							{
								LootTableSubProvider { consumer ->
									tables.forEach { (key, builder) -> consumer.accept(key, builder) }
								}
							},
							LootContextParamSets.BLOCK,
						),
					),
					registries,
				)
			}
		}
	}
}
