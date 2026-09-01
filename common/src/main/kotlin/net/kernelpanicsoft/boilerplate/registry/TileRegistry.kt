package net.kernelpanicsoft.boilerplate.registry

import dev.architectury.registry.client.rendering.BlockEntityRendererRegistry
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.archie.transfer.exposeEnergyStorage
import net.kernelpanicsoft.archie.transfer.exposeFluidStorage
import net.kernelpanicsoft.archie.transfer.exposeItemStorage
import net.kernelpanicsoft.archie.util.blockEntityType
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.attachment.FallbackItemStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.attachment.FallbackFluidStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.attachment.FluidStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.attachment.ItemStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.client.MultipartBlockEntityVisual
import net.kernelpanicsoft.boilerplate.pipe.client.MultipartTravelingItemRenderer
import net.kernelpanicsoft.boilerplate.pipe.client.TravelingItemBlockEntityRenderer
import net.kernelpanicsoft.boilerplate.pipe.entity.GlassPipeBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.boilerplate.power.*
import net.kernelpanicsoft.boilerplate.power.entity.CreativePressureSourceBlockEntity
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity
import net.kernelpanicsoft.boilerplate.warehouse.client.WarehouseControllerBlockEntityRenderer
import net.kernelpanicsoft.boilerplate.warehouse.client.WarehouseControllerVisual
import net.kernelpanicsoft.boilerplate.warehouse.rack.BulkRackBlockEntity
import net.kernelpanicsoft.boilerplate.warehouse.rack.GeneralRackBlockEntity
import net.kernelpanicsoft.boilerplate.warehouse.tank.FluidTankBlockEntity
import net.kernelpanicsoft.boilerplate.warehouse.rack.UnstackableRackBlockEntity
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.entity.BlockEntityType

/** Registers Boilerplate's block entity types. Only [Multipart]/[GlassPipe]/[WarehouseController] get renderers - a plain [Pipe]/[PressurePipe] never carries hooks or an encasement, so it renders through its own static blockstate instead. [Multipart] is the one promoted-segment type, shared by every underlying pipe kind (item or pressure) - see [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock]. */
object TileRegistry : ADeferredRegistryHolder<BlockEntityType<*>>(Boilerplate.MOD, Registries.BLOCK_ENTITY_TYPE) {
	val Pipe: BlockEntityType<PipeBlockEntity> by register("pipe") {
		blockEntityType(::PipeBlockEntity) {
			add(BlockRegistry.Pipe)
		}
	}

	/**
	 * One [MultipartBlockEntity] can carry up to six independent hooks, so a query with a real
	 * [direction] (the caller knows exactly which face it means - see
	 * [net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem.targetFace]'s own KDoc for how
	 * that survives delivery) always checks that specific face's own hook first - any
	 * [net.kernelpanicsoft.boilerplate.pipe.attachment.ItemStorageExposer] hook or encasement
	 * state answers here without this selector needing to know its concrete type; see that
	 * interface's own KDoc for how a new exposing type opts in. A face-less match falls through to
	 * the whole tile's own encasement, then to any
	 * [net.kernelpanicsoft.boilerplate.pipe.attachment.FallbackItemStorageExposer] hook on any
	 * face (first match) - the direction [exposeCommonItemStorage] passes is ordinarily whichever
	 * neighboring pipe segment an item is arriving *from* (pipe topology, unrelated to which face
	 * actually carries the hook in question), so most callers still don't have a specific face to
	 * offer; the fallback keeps those working. Only
	 * [net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookState] opts out of the fallback -
	 * see [FallbackItemStorageExposer]'s own KDoc for why (guessing wrong there means silently
	 * crossing a subnet boundary meant to stay isolated).
	 *
	 * The encasement check sits between those two hook tiers deliberately: after a hook matched on
	 * the query's own [direction], but ahead of the face-less hook fallback. An encasement has no
	 * face to mismatch on, so it's the one unambiguous answer for a direction-less query, and (for
	 * the Crafting CPU case specifically) a job's own pull-back must not be diverted into a pattern
	 * buffer that merely happens to share the segment.
	 *
	 * [exposePressureStorage] is the equivalent lookup for
	 * [net.kernelpanicsoft.boilerplate.power.PressureStorageExposer] encasements (the tank/
	 * compressor) - simpler, since only the whole-segment encasement can ever expose pressure, no
	 * per-hook/fallback tiers needed.
	 */
	val Multipart: BlockEntityType<MultipartBlockEntity> by register("hook") {
		blockEntityType(::MultipartBlockEntity) {
			add(BlockRegistry.Multipart)
		}
	}.apply {
		exposeItemStorage { tile, direction ->
			val hookAtFace = direction?.let { tile.hooks[it.name] }
			(hookAtFace as? ItemStorageExposer)?.exposedItemStorage(tile)
				?: (tile.encasement.value as? ItemStorageExposer)?.exposedItemStorage(tile)
				?: tile.hooks.firstNotNullOfOrNull { (it.value as? FallbackItemStorageExposer)?.exposedItemStorage(tile) }
		}
		exposeFluidStorage { tile, direction ->
			val hookAtFace = direction?.let { tile.hooks[it.name] }
			(hookAtFace as? FluidStorageExposer)?.exposedFluidStorage(tile)
				?: (tile.encasement.value as? FluidStorageExposer)?.exposedFluidStorage(tile)
				?: tile.hooks.firstNotNullOfOrNull { (it.value as? FallbackFluidStorageExposer)?.exposedFluidStorage(tile) }
		}
		exposePressureStorage { tile, direction ->
			val hookAtFace = direction?.let { tile.hooks[it.name] }
			(hookAtFace as? PressureStorageExposer)?.exposedPressureStorage(tile)
				?: (tile.encasement.value as? PressureStorageExposer)?.exposedPressureStorage(tile)
				?: tile.hooks.firstNotNullOfOrNull { (it.value as? FallbackPressureStorageExposer)?.exposedPressureStorage(tile) }
		}
		exposeEnergyStorage { tile, direction ->
			val hookAtFace = direction?.let { tile.hooks[it.name] }
			(hookAtFace as? EnergyStorageExposer)?.exposedEnergyStorage(tile)
				?: (tile.encasement.value as? EnergyStorageExposer)?.exposedEnergyStorage(tile)
				?: tile.hooks.firstNotNullOfOrNull { (it.value as? FallbackEnergyStorageExposer)?.exposedEnergyStorage(tile) }
		}
	}

	val GlassPipe: BlockEntityType<GlassPipeBlockEntity> by register("glass_pipe") {
		blockEntityType(::GlassPipeBlockEntity) {
			add(BlockRegistry.GlassPipe)
		}
	}

	/**
	 * [exposeItemStorage] is chained here, on the raw `RegistrySupplier` [register] returns, rather
	 * than on this property once resolved - unlike Fabric, NeoForge's `RegistrySupplier.get()`
	 * throws if called before the entry is actually bound, which [Boilerplate.init] calling
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

	/** Custom [net.kernelpanicsoft.boilerplate.warehouse.rack.UncappedItemStorage], not an [net.kernelpanicsoft.archie.transfer.ArchieItemStorage] - exposed via [exposeCommonItemStorage] rather than Archie's own `exposeItemStorage`, which is fixed to that one concrete type. */
	val BulkRack: BlockEntityType<BulkRackBlockEntity> by register("bulk_rack") {
		blockEntityType(::BulkRackBlockEntity) {
			add(BlockRegistry.BulkRack)
		}
	}.apply { exposeItemStorage(BulkRackBlockEntity::storage) }

	/** See [BulkRack]'s identical [exposeCommonItemStorage] note. */
	val UnstackableRack: BlockEntityType<UnstackableRackBlockEntity> by register("unstackable_rack") {
		blockEntityType(::UnstackableRackBlockEntity) {
			add(BlockRegistry.UnstackableRack)
		}
	}.apply { exposeItemStorage(UnstackableRackBlockEntity::storage) }

	/**
	 * Its own [BlockEntityType] registration (rather than reusing [Pipe]'s), even though both
	 * instantiate the same [PipeBlockEntity] class - vanilla's "valid blocks" set is per-
	 * [BlockEntityType], not per-class, and this keeps the two pipe kinds independently
	 * queryable/addressable. An explicit factory lambda rather than a bare `::PipeBlockEntity`
	 * reference: that resolves to [PipeBlockEntity]'s own 2-arg secondary constructor, which is
	 * hardcoded to [Pipe] - this type needs the 3-arg primary constructor instead, naming itself.
	 */
	val PressurePipe: BlockEntityType<PipeBlockEntity> by register("pressure_pipe") {
		blockEntityType({ pos, state -> PipeBlockEntity(PressurePipe, pos, state) }) {
			add(BlockRegistry.PressurePipe)
		}
	}

	val FluidTank: BlockEntityType<FluidTankBlockEntity> by register("fluid_tank") {
		blockEntityType(::FluidTankBlockEntity) {
			add(BlockRegistry.FluidTank)
		}
	}.apply {
		exposeFluidStorage { tile -> tile.storage }
	}

	val CreativePressureSource: BlockEntityType<CreativePressureSourceBlockEntity> by register("creative_pressure_source") {
		blockEntityType(::CreativePressureSourceBlockEntity) {
			add(BlockRegistry.CreativePressureSource)
		}
	}.apply {
		exposePressureStorage { tile -> tile.pressure }
	}

	override fun initClient() {
		SimpleBlockEntityVisualizer.builder(Multipart)
			.factory(::MultipartBlockEntityVisual)
			.neverSkipVanillaRender()
			.apply()
		BlockEntityRendererRegistry.register(Multipart, ::MultipartTravelingItemRenderer)
		BlockEntityRendererRegistry.register(GlassPipe, ::TravelingItemBlockEntityRenderer)
		SimpleBlockEntityVisualizer.builder(WarehouseController)
			.factory(::WarehouseControllerVisual)
			.apply()
		BlockEntityRendererRegistry.register(WarehouseController, ::WarehouseControllerBlockEntityRenderer)
	}
}
