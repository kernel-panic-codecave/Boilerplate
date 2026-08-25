package net.kernelpanicsoft.tubularstorage.datagen

import net.kernelpanicsoft.tubularstorage.pipe.attachment.AttachmentHolderState
import net.kernelpanicsoft.tubularstorage.pipe.attachment.PipeAttachmentType
import net.kernelpanicsoft.tubularstorage.pipe.block.MultipartContentsLootFunction
import net.kernelpanicsoft.tubularstorage.pipe.encasement.EncasementHolderState
import net.kernelpanicsoft.tubularstorage.pipe.encasement.PipeEncasementType
import net.kernelpanicsoft.tubularstorage.pipe.hook.HookHolderState
import net.kernelpanicsoft.tubularstorage.pipe.hook.PipeHookType
import net.kernelpanicsoft.tubularstorage.registry.BlockRegistry
import net.kernelpanicsoft.tubularstorage.registry.EncasementTypeRegistry
import net.kernelpanicsoft.tubularstorage.registry.HookTypeRegistry
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ItemLike
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.storage.loot.LootPool
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.level.storage.loot.entries.LootItem
import net.minecraft.world.level.storage.loot.predicates.ExplosionCondition
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue

/**
 * Builds Tubular Storage's loot tables:
 *
 * - every player-facing block simply drops itself - the wrench-gating lives in the block
 *   properties (`requiresCorrectToolForDrops`) and the `tubularstorage:mineable/wrench` tag, not
 *   here;
 * - the hidden part blocks get empty tables: they are never placed or broken through normal play,
 *   so an explicit empty table only keeps vanilla's missing-loot-table validation quiet;
 * - the multipart segment drops its contents piecewise - one entry per possible attachment plus
 *   the underlying pipe, each a placeholder stack that [MultipartContentsLootFunction] replaces
 *   with the actual item read off the dying block entity, or empties when that part isn't there;
 * - each attachment type gets its own detach table (see [PipeHookType.detachLootTableId] /
 *   [PipeEncasementType.detachLootTableId]) rolled when that part is removed from a surviving
 *   segment, defaulting to a single survives-explosion entry yielding the type's own item. This is
 *   the per-type seam for custom detach behavior - an empty table makes it drop nothing ("fragile"),
 *   a `random_chance`-conditioned pool gives it break odds.
 */
internal fun tubularStorageLootTables(): Map<ResourceKey<LootTable>, LootTable.Builder> {
	val tables = HashMap<ResourceKey<LootTable>, LootTable.Builder>()

	for (block in SELF_DROPS) {
		tables[block.lootTable] = LootTable.lootTable().withPool(
			LootPool.lootPool()
				.setRolls(ConstantValue.exactly(1f))
				.add(LootItem.lootTableItem(block))
		)
	}

	for (block in HIDDEN_PART_BLOCKS) {
		tables[block.lootTable] = LootTable.lootTable()
	}

	for (type in DETACH_HOOK_TYPES) {
		tables[detachKey(type.detachLootTableId)] = detachDropTable(type)
	}
	for (type in DETACH_ENCASEMENT_TYPES) {
		tables[detachKey(type.detachLootTableId)] = detachDropTable(type)
	}

	with(BlockRegistry.Multipart) {
		val builder = LootTable.lootTable()
		fun part(part: MultipartContentsLootFunction.Part) {
			builder.withPool(
				LootPool.lootPool()
					.setRolls(ConstantValue.exactly(1f))
					.add(LootItem.lootTableItem(BlockRegistry.Pipe).apply(MultipartContentsLootFunction.Builder(part)))
			)
		}
		part(MultipartContentsLootFunction.Part.PIPE)
		part(MultipartContentsLootFunction.Part.ENCASEMENT)
		for (direction in Direction.entries) {
			part(MultipartContentsLootFunction.Part.valueOf("HOOK_${direction.name}"))
		}
		tables[lootTable] = builder
	}

	return tables
}

/** Maps a detach table id to its loot-table registry key. */
private fun detachKey(id: ResourceLocation): ResourceKey<LootTable> = ResourceKey.create(Registries.LOOT_TABLE, id)

/** The default detach table: one survives-explosion pool yielding the attachment type's own item. */
private fun <S : AttachmentHolderState> detachDropTable(type: PipeAttachmentType<S>): LootTable.Builder = LootTable.lootTable().withPool(
	LootPool.lootPool()
		.setRolls(ConstantValue.exactly(1f))
		.`when`(ExplosionCondition.survivesExplosion())
		.add(LootItem.lootTableItem(type))
)

/** Hook types getting a generated detach table - see [PipeHookType.detachLootTableId]. */
private val DETACH_HOOK_TYPES: List<PipeHookType<out HookHolderState>> = listOf(
	HookTypeRegistry.Extraction,
	HookTypeRegistry.Filter,
	HookTypeRegistry.Provider,
	HookTypeRegistry.Sync,
	HookTypeRegistry.Requester,
	HookTypeRegistry.Terminal,
	HookTypeRegistry.Interface,
	HookTypeRegistry.CraftingTerminal,
	HookTypeRegistry.PatternProvider,
	HookTypeRegistry.PatternTerminal,
)

/** Encasement types getting a generated detach table - see [PipeEncasementType.detachLootTableId]. */
private val DETACH_ENCASEMENT_TYPES: List<PipeEncasementType<out EncasementHolderState>> = listOf(
	EncasementTypeRegistry.CraftingBuffer,
)

/** Blocks that drop themselves when broken with the right tool. */
private val SELF_DROPS: List<Block> = listOf(
	BlockRegistry.Pipe,
	BlockRegistry.GlassPipe,
	BlockRegistry.WarehouseController,
	BlockRegistry.GantryRail,
	BlockRegistry.GeneralRack,
	BlockRegistry.BulkRack,
	BlockRegistry.UnstackableRack,
)

/** The hidden per-attachment model blocks - present in the registry for model/baking purposes only, never obtainable. */
private val HIDDEN_PART_BLOCKS: List<Block> = listOf(
	BlockRegistry.ExtractionHook,
	BlockRegistry.FilterHook,
	BlockRegistry.ProviderHook,
	BlockRegistry.SyncHook,
	BlockRegistry.RequesterHook,
	BlockRegistry.TerminalHook,
	BlockRegistry.InterfaceHook,
	BlockRegistry.CraftingTerminalHook,
	BlockRegistry.PatternProviderHook,
	BlockRegistry.PatternTerminalHook,
	BlockRegistry.CraftingBufferPart,
)
