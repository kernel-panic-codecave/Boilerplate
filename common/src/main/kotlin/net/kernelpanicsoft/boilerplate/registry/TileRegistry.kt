package net.kernelpanicsoft.boilerplate.registry

import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer
import earth.terrarium.common_storage_lib.resources.fluid.FluidResource
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.archie.transfer.exposeEnergyStorage
import net.kernelpanicsoft.archie.transfer.exposeFluidStorage
import net.kernelpanicsoft.archie.transfer.exposeItemStorage
import net.kernelpanicsoft.archie.util.blockEntityType
import net.kernelpanicsoft.archie.util.onClient
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.network.ResourceKind
import net.kernelpanicsoft.boilerplate.pipe.attachment.FallbackFluidStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.attachment.FallbackItemStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.attachment.FluidStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.attachment.ItemStorageExposer
import net.kernelpanicsoft.boilerplate.pipe.client.GlassPipeVisual
import net.kernelpanicsoft.boilerplate.pipe.client.MultipartBlockEntityVisual
import net.kernelpanicsoft.boilerplate.pipe.entity.GlassPipeBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.PassThroughStorage
import net.kernelpanicsoft.boilerplate.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.boilerplate.power.*
import net.kernelpanicsoft.boilerplate.power.entity.CreativePressureSourceBlockEntity
import net.kernelpanicsoft.boilerplate.warehouse.WarehouseControllerBlockEntity
import net.kernelpanicsoft.boilerplate.warehouse.client.WarehouseControllerVisual
import net.kernelpanicsoft.boilerplate.warehouse.rack.BulkRackBlockEntity
import net.kernelpanicsoft.boilerplate.warehouse.rack.GeneralRackBlockEntity
import net.kernelpanicsoft.boilerplate.warehouse.rack.UnstackableRackBlockEntity
import net.kernelpanicsoft.boilerplate.warehouse.tank.FluidTankBlockEntity
import net.kernelpanicsoft.boilerplate.network.exposeResourceStorage
import net.kernelpanicsoft.boilerplate.pipe.attachment.exposedStorageFor
import earth.terrarium.common_storage_lib.resources.ResourceComponent
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.entity.BlockEntityType

/** Registers Boilerplate's block entity types. Only [Multipart]/[GlassPipe]/[WarehouseController] get renderers - a plain [Pipe]/[PressurePipe] never carries hooks or an encasement, so it renders through its own static blockstate instead. [Multipart] is the one promoted-segment type, shared by every underlying pipe kind (item or pressure) - see [net.kernelpanicsoft.boilerplate.pipe.block.MultipartBlock]. */
object TileRegistry : ADeferredRegistryHolder<BlockEntityType<*>>(Boilerplate.MOD, Registries.BLOCK_ENTITY_TYPE) {
	val Pipe: BlockEntityType<PipeBlockEntity> by register("pipe") {
		blockEntityType(::PipeBlockEntity) {
			add(BlockRegistry.Pipe)
		}
	}

	val Multipart: BlockEntityType<MultipartBlockEntity> by register("multipart") {
		blockEntityType(::MultipartBlockEntity) {
			add(BlockRegistry.Multipart)
		}
	}

	val GlassPipe: BlockEntityType<GlassPipeBlockEntity> by register("glass_pipe") {
		blockEntityType(::GlassPipeBlockEntity) {
			add(BlockRegistry.GlassPipe)
		}
	}

	val WarehouseController: BlockEntityType<WarehouseControllerBlockEntity> by register("warehouse_controller") {
		blockEntityType(::WarehouseControllerBlockEntity) {
			add(BlockRegistry.WarehouseController)
		}
	}

	val GeneralRack: BlockEntityType<GeneralRackBlockEntity> by register("general_rack") {
		blockEntityType(::GeneralRackBlockEntity) {
			add(BlockRegistry.GeneralRack)
		}
	}

	val BulkRack: BlockEntityType<BulkRackBlockEntity> by register("bulk_rack") {
		blockEntityType(::BulkRackBlockEntity) {
			add(BlockRegistry.BulkRack)
		}
	}

	val UnstackableRack: BlockEntityType<UnstackableRackBlockEntity> by register("unstackable_rack") {
		blockEntityType(::UnstackableRackBlockEntity) {
			add(BlockRegistry.UnstackableRack)
		}
	}

	val PressurePipe: BlockEntityType<PipeBlockEntity> by register("pressure_pipe") {
		blockEntityType({ pos, state -> PipeBlockEntity(PressurePipe, pos, state) }) {
			add(BlockRegistry.PressurePipe)
		}
	}

	val FluidTank: BlockEntityType<FluidTankBlockEntity> by register("fluid_tank") {
		blockEntityType(::FluidTankBlockEntity) {
			add(BlockRegistry.FluidTank)
		}
	}

	val CreativePressureSource: BlockEntityType<CreativePressureSourceBlockEntity> by register("creative_pressure_source") {
		blockEntityType(::CreativePressureSourceBlockEntity) {
			add(BlockRegistry.CreativePressureSource)
		}
	}

	override fun init() {
		super.init()
		listen {
			Multipart.apply {
				// Every registered kind at once, through the one resolution they all share - not a
				// hand-written lambda per kind. That is what makes a kind registered by a *loader*
				// (chemicals, on NeoForge) reachable from the outside without a registration of its
				// own: it used to need one, and the two it did not have were exactly the two bugs
				// that followed. Safe to enumerate the registry here because `listen` runs after
				// every kind has registered, which is not true of `init` itself.
				exposeResourceStorage { tile, direction, kind -> tile.exposedStorageFor<ResourceComponent>(kind, direction) }
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

			// A controller's staging buffer for every kind it can hold, on every face - the twin of
			// the Multipart registration above, and the reason a chemical delivery can find one.
			WarehouseController.exposeResourceStorage { tile, _, kind -> tile.inboundFor<ResourceComponent>(kind) }

			// A plain pipe has no hooks to consult - every face of it is a pass-through, which is the
			// whole point: a machine's own auto-output should reach the network by being pointed at
			// a pipe, with no hook in between. See PassThroughStorage.
			@Suppress("UNCHECKED_CAST")
			Pipe.apply {
				exposeItemStorage { tile, direction -> passThroughFace(tile, direction, ResourceKindRegistry.Item) as CommonStorage<ItemResource>? }
				exposeFluidStorage { tile, direction -> passThroughFace(tile, direction, ResourceKindRegistry.Fluid) as CommonStorage<FluidResource>? }
			}
			@Suppress("UNCHECKED_CAST")
			GlassPipe.apply {
				exposeItemStorage { tile, direction -> passThroughFace(tile, direction, ResourceKindRegistry.Item) as CommonStorage<ItemResource>? }
				exposeFluidStorage { tile, direction -> passThroughFace(tile, direction, ResourceKindRegistry.Fluid) as CommonStorage<FluidResource>? }
			}

			GeneralRack.exposeItemStorage(GeneralRackBlockEntity::storage)
			BulkRack.exposeItemStorage(BulkRackBlockEntity::storage)
			UnstackableRack.exposeItemStorage(UnstackableRackBlockEntity::storage)
			FluidTank.exposeFluidStorage(FluidTankBlockEntity::storage)

			CreativePressureSource.exposePressureStorage(CreativePressureSourceBlockEntity::pressure)

			onClient {
				SimpleBlockEntityVisualizer.builder(Multipart)
					.factory(::MultipartBlockEntityVisual)
					.apply()
				SimpleBlockEntityVisualizer.builder(GlassPipe)
					.factory(::GlassPipeVisual)
					.apply()
				SimpleBlockEntityVisualizer.builder(WarehouseController)
					.factory(::WarehouseControllerVisual)
					.apply()
			}
		}
	}
}

/**
 * The pass-through [kind] sees on [direction] of [tile] - what a face with nothing more specific to
 * offer presents, so a machine pushing at a pipe reaches the network.
 *
 * A hook that exposes a storage of its own answers *instead* of this, and so is responsible for
 * presenting a pass-through itself if a machine should be able to push at its face - see
 * [net.kernelpanicsoft.boilerplate.pipe.hook.InterfaceHookState] and
 * [net.kernelpanicsoft.boilerplate.pipe.hook.PatternProviderHookState], which both do.
 *
 * `null` for a direction-less query. A pass-through has to know which side it was pushed from, both
 * to keep the resource from bouncing straight back into the pusher and to start the delivery
 * travelling the right way; "some face, unspecified" cannot answer either. Every real push names a
 * side.
 */
private fun passThroughFace(tile: PipeBlockEntity, direction: Direction?, kind: ResourceKind): CommonStorage<*>? {
	val face = direction ?: return null
	return PassThroughStorage.of(tile, face, kind)
}
