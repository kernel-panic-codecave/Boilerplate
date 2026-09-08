package net.kernelpanicsoft.boilerplate.warehouse.tank

import dev.architectury.registry.menu.ExtendedMenuProvider
import earth.terrarium.common_storage_lib.resources.fluid.util.FluidAmounts
import net.kernelpanicsoft.archie.block.entity.NBTBlockEntity
import net.kernelpanicsoft.archie.serialization.Sync
import net.kernelpanicsoft.archie.transfer.ArchieFluidStorage
import net.kernelpanicsoft.boilerplate.registry.TileRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.block.state.BlockState

/**
 * A single-fluid tank - the fluid network's counterpart to the item racks
 * ([net.kernelpanicsoft.boilerplate.warehouse.rack.GeneralRackBlockEntity] and friends), and the
 * first block in the mod to expose fluid storage at all.
 *
 * One slot, not several: a tank holds one fluid, and a multi-slot fluid container is a different
 * (and much less common) machine. Filled and drained entirely through
 * [earth.terrarium.common_storage_lib.fluid.FluidApi] by whatever touches it - a pipe's extraction
 * hook, the gantry, another mod's pump - exactly like a rack. Right-clicking opens
 * [FluidTankScreen], which draws the contents rather than printing them to the action bar.
 *
 * @see net.kernelpanicsoft.boilerplate.warehouse.WarehouseIndex for how a tank inside a bound
 * warehouse volume becomes indexed storage the gantry can use.
 */
class FluidTankBlockEntity(pos: BlockPos, state: BlockState) :
	NBTBlockEntity(TileRegistry.FluidTank, pos, state), ExtendedMenuProvider {
	/**
	 * This tank's contents, synced so [FluidTankScreen] can draw them.
	 *
	 * The design doc recorded this as un-syncable, on the grounds that Archie resolves a packet
	 * serializer from the property's type and a `FluidResource` has none. That is true of a *bare*
	 * `FluidResource` field and not of this: `fluidField` yields an `ArchieFluidStorage`, which
	 * carries its own `@Serializable` serializer and is registered for sync by exactly the same code
	 * path `itemField` uses.
	 */
	@Sync
	val storage: ArchieFluidStorage by fluidField(capacity, size = 1)

	/** A one-line summary of what this tank holds, in millibuckets - the unit a player actually thinks in, converted back from whatever the platform counts internally. */
	fun describeContents(): Component {
		val resource = storage.getResource(0)
		if (resource.isBlank) return Component.literal("Fluid Tank: empty")
		val name = BuiltInRegistries.FLUID.getKey(resource.type)
		val storedMb = FluidAmounts.toMillibuckets(storage.getAmount(0))
		val capacityMb = FluidAmounts.toMillibuckets(capacity)
		return Component.literal("Fluid Tank: $name $storedMb/$capacityMb mB")
	}

	override fun saveExtraData(buf: FriendlyByteBuf) {
		buf.writeBlockPos(blockPos)
	}

	override fun getDisplayName(): Component = describeContents()

	override fun createMenu(i: Int, inventory: Inventory, player: Player): AbstractContainerMenu = FluidTankMenu(i, inventory, this)

	companion object {
		/** This tank's capacity, in millibuckets - the loader-independent way to state it. */
		const val CAPACITY_MILLIBUCKETS = 16_000L

		/**
		 * [CAPACITY_MILLIBUCKETS] in whatever unit this platform counts fluid in.
		 *
		 * Computed on every read rather than stored, because it must not be captured before the
		 * platform's own conversion is available, and deliberately routed through [FluidAmounts.toPlatformAmount]
		 * rather than any `FluidAmounts` constant - those all read `0` in Common Storage Lib 0.0.5
		 * (see [net.kernelpanicsoft.boilerplate.pipe.network.FluidNetworkType.extractionBatch]), and
		 * a zero-capacity tank would silently accept nothing at all.
		 */
		val capacity: Long by lazy { FluidAmounts.toPlatformAmount(CAPACITY_MILLIBUCKETS) }
	}
}
