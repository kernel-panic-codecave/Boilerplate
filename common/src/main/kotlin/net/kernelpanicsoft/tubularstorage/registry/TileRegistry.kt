package net.kernelpanicsoft.tubularstorage.registry

import dev.architectury.registry.client.rendering.BlockEntityRendererRegistry
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.archie.transfer.exposeItemStorage
import net.kernelpanicsoft.archie.util.blockEntityType
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.crafting.AssemblyTableBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.client.HookBlockEntityVisual
import net.kernelpanicsoft.tubularstorage.pipe.client.PipeHookBlockEntityRenderer
import net.kernelpanicsoft.tubularstorage.pipe.client.TravelingItemBlockEntityRenderer
import net.kernelpanicsoft.tubularstorage.pipe.entity.GlassPipeBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.tubularstorage.pipe.hook.InterfaceHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternBufferIO
import net.kernelpanicsoft.tubularstorage.pipe.hook.PatternProviderHookState
import net.kernelpanicsoft.tubularstorage.pipe.hook.TerminalHookState
import net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity
import net.kernelpanicsoft.tubularstorage.warehouse.client.WarehouseControllerBlockEntityRenderer
import net.kernelpanicsoft.tubularstorage.warehouse.client.WarehouseControllerVisual
import net.kernelpanicsoft.tubularstorage.warehouse.rack.BulkRackBlockEntity
import net.kernelpanicsoft.tubularstorage.warehouse.rack.GeneralRackBlockEntity
import net.kernelpanicsoft.tubularstorage.warehouse.rack.UnstackableRackBlockEntity
import net.kernelpanicsoft.tubularstorage.warehouse.rack.exposeRackStorage
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.entity.BlockEntityType

/** Registers Tubular Storage's block entity types. Only [Hook]/[GlassPipe]/[WarehouseController] get renderers - a plain [Pipe] never carries hooks or renders its contents. */
object TileRegistry : ADeferredRegistryHolder<BlockEntityType<*>>(TubularStorage.MOD, Registries.BLOCK_ENTITY_TYPE) {
	val Pipe: BlockEntityType<PipeBlockEntity> by register("pipe") {
		blockEntityType(::PipeBlockEntity) {
			add(BlockRegistry.Pipe)
		}
	}

	/**
	 * One [HookBlockEntity] can carry up to six independent hooks, so a query with a real [direction]
	 * (the caller knows exactly which face it means - see
	 * [net.kernelpanicsoft.tubularstorage.pipe.entity.TravelingItem.targetFace]'s own KDoc for how
	 * that survives delivery) always checks that specific face first, for every hook type that can
	 * legitimately repeat across faces of the same block: [InterfaceHookState.stock],
	 * [PatternBufferIO] (over a [PatternProviderHookState]), and [TerminalHookState.output].
	 * [InterfaceHookState] stops there - a direction-less query (no face to check against) resolves
	 * to `null` rather than guessing, since guessing wrong there means silently crossing a subnet
	 * boundary meant to stay isolated. [PatternBufferIO]/[TerminalHookState.output], by contrast,
	 * fall back to [patternBufferOf]/[terminalOutputOf]'s own first-match-on-any-face search when
	 * the query's own [direction] is `null` or doesn't land on a matching hook - the direction
	 * [exposeRackStorage] passes is ordinarily whichever neighboring pipe segment an item is
	 * arriving *from* (pipe topology, unrelated to which face actually carries the hook in
	 * question), so most callers still don't have a specific face to offer; the fallback keeps
	 * those working exactly as before; only a caller that resolved [PatternProviderSource]/[TerminalHookState]
	 * up front and threaded its own [direction] all the way through (a
	 * [net.kernelpanicsoft.tubularstorage.crafting.CraftingJob] step's own delivery, a terminal
	 * withdrawing to itself) gets genuinely disambiguated when two same-type hooks share a block.
	 * [PatternBufferIO] itself isn't an [ArchieItemStorage], so this uses [exposeRackStorage] rather
	 * than Archie's own `exposeItemStorage`, whose signature is fixed to that one concrete type -
	 * see [BulkRack]/[UnstackableRack]/[AssemblyTable]'s identical note.
	 */
	val Hook: BlockEntityType<HookBlockEntity> by register("hook") {
		blockEntityType(::HookBlockEntity) {
			add(BlockRegistry.Hook)
		}
	}.apply {
		exposeRackStorage { tile, direction ->
			val hookAtFace = direction?.let { tile.hooks[it.name] }
			(hookAtFace as? InterfaceHookState)?.stock
				?: (hookAtFace as? PatternProviderHookState)?.let { PatternBufferIO(it) }
				?: (hookAtFace as? TerminalHookState)?.output
				?: patternBufferOf(tile)
				?: terminalOutputOf(tile)
		}
	}

	private fun patternBufferOf(tile: HookBlockEntity): PatternBufferIO? {
		for ((_, entry) in tile.hooks) (entry as? PatternProviderHookState)?.let { return PatternBufferIO(it) }
		return null
	}

	private fun terminalOutputOf(tile: HookBlockEntity): ArchieItemStorage? {
		for ((_, entry) in tile.hooks) (entry as? TerminalHookState)?.let { return it.output }
		return null
	}

	val GlassPipe: BlockEntityType<GlassPipeBlockEntity> by register("glass_pipe") {
		blockEntityType(::GlassPipeBlockEntity) {
			add(BlockRegistry.GlassPipe)
		}
	}

	/**
	 * [exposeItemStorage] is chained here, on the raw `RegistrySupplier` [register] returns, rather
	 * than on this property once resolved - unlike Fabric, NeoForge's `RegistrySupplier.get()`
	 * throws if called before the entry is actually bound, which [TubularStorage.init] calling
	 * straight after [init] (still inside `FMLConstructModEvent`) is too early for. Chaining on the
	 * supplier instead defers via `RegistrySupplier.listen(...)`, which waits for the entry to
	 * actually register - confirmed the hard way via a `runGametest` crash on NeoForge specifically.
	 */
	val WarehouseController: BlockEntityType<WarehouseControllerBlockEntity> by register("warehouse_controller") {
		blockEntityType(::WarehouseControllerBlockEntity) {
			add(BlockRegistry.WarehouseController)
		}
	}.apply { exposeItemStorage(WarehouseControllerBlockEntity::inboundBuffer) }

	val GeneralRack: BlockEntityType<GeneralRackBlockEntity> by register("general_rack") {
		blockEntityType(::GeneralRackBlockEntity) {
			add(BlockRegistry.GeneralRack)
		}
	}.apply { exposeItemStorage(GeneralRackBlockEntity::storage) }

	/** Custom [net.kernelpanicsoft.tubularstorage.warehouse.rack.UncappedItemStorage], not an [net.kernelpanicsoft.archie.transfer.ArchieItemStorage] - exposed via [exposeRackStorage] rather than Archie's own `exposeItemStorage`, which is fixed to that one concrete type. */
	val BulkRack: BlockEntityType<BulkRackBlockEntity> by register("bulk_rack") {
		blockEntityType(::BulkRackBlockEntity) {
			add(BlockRegistry.BulkRack)
		}
	}.apply { exposeRackStorage(BulkRackBlockEntity::storage) }

	/** See [BulkRack]'s identical [exposeRackStorage] note. */
	val UnstackableRack: BlockEntityType<UnstackableRackBlockEntity> by register("unstackable_rack") {
		blockEntityType(::UnstackableRackBlockEntity) {
			add(BlockRegistry.UnstackableRack)
		}
	}.apply { exposeRackStorage(UnstackableRackBlockEntity::storage) }

	/** [AssemblyTableBlockEntity.ioStorage] is a custom [earth.terrarium.common_storage_lib.storage.base.CommonStorage], not an [net.kernelpanicsoft.archie.transfer.ArchieItemStorage] - exposed via [exposeRackStorage] for the same reason [BulkRack]/[UnstackableRack] are (that helper isn't rack-specific, just generic over any `CommonStorage`). */
	val AssemblyTable: BlockEntityType<AssemblyTableBlockEntity> by register("assembly_table") {
		blockEntityType(::AssemblyTableBlockEntity) {
			add(BlockRegistry.AssemblyTable)
		}
	}.apply { exposeRackStorage(AssemblyTableBlockEntity::ioStorage) }

	override fun initClient() {
		SimpleBlockEntityVisualizer.builder(Hook)
			.factory(::HookBlockEntityVisual)
			.neverSkipVanillaRender()
			.apply()
		BlockEntityRendererRegistry.register(Hook, ::PipeHookBlockEntityRenderer)
		BlockEntityRendererRegistry.register(GlassPipe, ::TravelingItemBlockEntityRenderer)
		SimpleBlockEntityVisualizer.builder(WarehouseController)
			.factory(::WarehouseControllerVisual)
			.apply()
		BlockEntityRendererRegistry.register(WarehouseController, ::WarehouseControllerBlockEntityRenderer)
	}
}
