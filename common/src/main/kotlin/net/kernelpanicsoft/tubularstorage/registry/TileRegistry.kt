package net.kernelpanicsoft.tubularstorage.registry

import dev.architectury.registry.client.rendering.BlockEntityRendererRegistry
import net.kernelpanicsoft.archie.registries.ADeferredRegistryHolder
import net.kernelpanicsoft.archie.transfer.exposeItemStorage
import net.kernelpanicsoft.archie.util.blockEntityType
import net.kernelpanicsoft.tubularstorage.TubularStorage
import net.kernelpanicsoft.tubularstorage.pipe.client.PipeHookBlockEntityRenderer
import net.kernelpanicsoft.tubularstorage.pipe.client.TravelingItemBlockEntityRenderer
import net.kernelpanicsoft.tubularstorage.pipe.entity.GlassPipeBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.entity.PipeBlockEntity
import net.kernelpanicsoft.tubularstorage.warehouse.WarehouseControllerBlockEntity
import net.kernelpanicsoft.tubularstorage.warehouse.client.WarehouseControllerBlockEntityRenderer
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.entity.BlockEntityType

/** Registers Tubular Storage's block entity types. Only [Hook]/[GlassPipe]/[WarehouseController] get renderers - a plain [Pipe] never carries hooks or renders its contents. */
object TileRegistry : ADeferredRegistryHolder<BlockEntityType<*>>(TubularStorage.MOD, Registries.BLOCK_ENTITY_TYPE) {
	val Pipe: BlockEntityType<PipeBlockEntity> by register("pipe") {
		blockEntityType(::PipeBlockEntity) {
			add(BlockRegistry.Pipe)
		}
	}

	val Hook: BlockEntityType<HookBlockEntity> by register("hook") {
		blockEntityType(::HookBlockEntity) {
			add(BlockRegistry.Hook)
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

	override fun initClient() {
		BlockEntityRendererRegistry.register(Hook, ::PipeHookBlockEntityRenderer)
		BlockEntityRendererRegistry.register(GlassPipe, ::TravelingItemBlockEntityRenderer)
		BlockEntityRendererRegistry.register(WarehouseController, ::WarehouseControllerBlockEntityRenderer)
	}
}
