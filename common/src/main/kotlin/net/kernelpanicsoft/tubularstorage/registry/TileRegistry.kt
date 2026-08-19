package net.kernelpanicsoft.tubularstorage.registry

import dev.architectury.registry.client.rendering.BlockEntityRendererRegistry
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.archie.transfer.exposeItemStorage
import net.kernelpanicsoft.archie.util.blockEntityType
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.client.HookBlockEntityVisual
import net.kernelpanicsoft.tubularstorage.pipe.client.PipeHookBlockEntityRenderer
import net.kernelpanicsoft.tubularstorage.pipe.client.TravelingItemBlockEntityRenderer
import net.kernelpanicsoft.tubularstorage.pipe.entity.GlassPipeBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.hook.InterfaceHookState
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
	 * [exposeItemStorage] here is direction-gated, unlike [WarehouseController]'s own - one
	 * [HookBlockEntity] can carry up to six independent hooks, and only the one specific face
	 * actually carrying an [InterfaceHookState] should ever answer a storage query. A
	 * direction-less query (no face to check against) resolves to `null` rather than guessing
	 * which face was meant.
	 */
	val Hook: BlockEntityType<HookBlockEntity> by register("hook") {
		blockEntityType(::HookBlockEntity) {
			add(BlockRegistry.Hook)
		}
	}.apply { exposeItemStorage { tile, direction -> (direction?.let { tile.hooks[it.name] } as? InterfaceHookState)?.stock } }

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
