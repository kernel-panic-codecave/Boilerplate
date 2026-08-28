package net.kernelpanicsoft.tubularstorage.datagen

import net.kernelpanicsoft.archie.data.ADataGenerator
import net.kernelpanicsoft.archie.data.ADatagenEventObject
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.registry.ItemRegistry
import net.kernelpanicsoft.tubularstorage.registry.TagsRegistry
import net.minecraft.data.loot.LootTableProvider
import net.minecraft.data.loot.LootTableSubProvider
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets

/**
 * Registers Tubular Storage's datagen providers. Only ever touched from behind
 * [net.kernelpanicsoft.archie.data.platform.ADataGeneratorPlatform.isDataGen] - see
 * [TubularStorage.init] - so `archie-datagen-common`, a dev-only dependency absent from the
 * production runtime classpath, is never resolved outside of a `runDatagen` launch.
 */
internal object TubularStorageDatagen : ADatagenEventObject(TubularStorage.MOD) {
	override fun ADataGenerator.handler() {
		client {
			blockStates {
				tubularStorageBlockStates()
			}
			languages {
				addBlock("Pipe") { BlockRegistry.Pipe }
				addBlock("Glass Pipe") { BlockRegistry.GlassPipe }
				addBlock("Warehouse Controller") { BlockRegistry.WarehouseController }
				addBlock("Gantry Rail") { BlockRegistry.GantryRail }
				addBlock("General Rack") { BlockRegistry.GeneralRack }
				addBlock("Bulk Rack") { BlockRegistry.BulkRack }
				addBlock("Unstackable Rack") { BlockRegistry.UnstackableRack }

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
			blockTags { _ ->
				TagsRegistry.Blocks.MINEABLE_WRENCH += BlockRegistry.Pipe
				TagsRegistry.Blocks.MINEABLE_WRENCH += BlockRegistry.GlassPipe
				TagsRegistry.Blocks.MINEABLE_WRENCH += BlockRegistry.Multipart
				TagsRegistry.Blocks.MINEABLE_WRENCH += BlockRegistry.WarehouseController
				TagsRegistry.Blocks.MINEABLE_WRENCH += BlockRegistry.GantryRail
				TagsRegistry.Blocks.MINEABLE_WRENCH += BlockRegistry.GeneralRack
				TagsRegistry.Blocks.MINEABLE_WRENCH += BlockRegistry.BulkRack
				TagsRegistry.Blocks.MINEABLE_WRENCH += BlockRegistry.UnstackableRack
			}
			itemTags { _ ->
				TagsRegistry.Items.TOOLS_WRENCH += ItemRegistry.BrassWrench
				TagsRegistry.Items.TOOLS_WRENCH += ItemRegistry.DiamondWrench
			}
			addProvider(isServer) { output, registries ->
				val tables = tubularStorageLootTables()
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
