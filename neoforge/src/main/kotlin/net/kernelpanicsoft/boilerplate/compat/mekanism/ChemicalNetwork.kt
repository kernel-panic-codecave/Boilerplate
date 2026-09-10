package net.kernelpanicsoft.boilerplate.compat.mekanism

import earth.terrarium.common_storage_lib.lookup.BlockLookup
import earth.terrarium.common_storage_lib.storage.base.CommonStorage
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.crafting.CraftingCpuRuntime
import net.kernelpanicsoft.boilerplate.debug.ResourceTrace
import net.kernelpanicsoft.boilerplate.resource.SResourceStack
import net.kernelpanicsoft.boilerplate.pipe.hook.SortingHookState
import net.kernelpanicsoft.boilerplate.pipe.network.*
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.LevelAccessor
import java.util.*

/** The chemical-pipe network - see [AbstractPipeNetwork] for the shared topology mechanics. */
class ChemicalPipeNetwork(id: UUID) : AbstractPipeNetwork(id)

/** The chemical-pipe [AbstractPipeNetworkManager] - members are [ChemicalNetworkType] positions. */
class ChemicalNetworkManager private constructor() : AbstractPipeNetworkManager<ChemicalPipeNetwork>() {
	override fun createNetwork(id: UUID): ChemicalPipeNetwork = ChemicalPipeNetwork(id)

	override fun isMember(level: ServerLevel, pos: BlockPos): Boolean = ChemicalPipeRouter.isPipe(level, pos)

	companion object {
		private val byLevel = WeakHashMap<ServerLevel, ChemicalNetworkManager>()

		fun get(level: ServerLevel): ChemicalNetworkManager = byLevel.getOrPut(level) { ChemicalNetworkManager() }
	}
}

/** Routes chemicals over the pipe network - the chemical twin of the item and fluid routers. */
object ChemicalPipeRouter : PipeRouter<ChemicalResource>() {
	override val api: BlockLookup<CommonStorage<ChemicalResource>, Direction?> get() = ChemicalApi.BLOCK

	/**
	 * The same [SortingHookState.accepts] every other kind uses. A chemical is an ordinary input to
	 * a filter card: a mod, tag or regex card judges it directly, while an item ghost grid never
	 * matches one - which the card's own mode then turns into the right answer.
	 */
	override fun acceptsByFilter(hook: SortingHookState, resource: ChemicalResource, color: DyeColor?): Boolean =
		hook.accepts(resource, color)

	override fun managerFor(level: ServerLevel): AbstractPipeNetworkManager<*> = ChemicalNetworkManager.get(level)

	override fun isPipe(level: LevelAccessor, pos: BlockPos): Boolean = ChemicalNetworkType in networkTypesAt(level, pos)

	/** A Crafting CPU mid-job outranks storage for a chemical it is still short of, exactly as for the other kinds. */
	override fun awaitsDelivery(level: ServerLevel, pos: BlockPos, resource: ChemicalResource): Boolean =
		CraftingCpuRuntime.awaitsDelivery(level, pos, resource)
}

/**
 * The chemical pipe network - what actually makes a pipe *carry* a chemical.
 *
 * Registering a [net.kernelpanicsoft.boilerplate.resource.ResourceKind] makes chemicals storable,
 * craftable and listable; this is the separate half that makes them *movable*. Both are needed, and
 * the split is easy to miss: a kind with no network type is stored perfectly and never goes
 * anywhere.
 *
 * [PipeCarriage.PRIMARY] so a plain pipe carries chemicals alongside items and fluids, without
 * touching [net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock] at all.
 */
object ChemicalNetworkType : ResourceNetworkType<ChemicalResource>(ChemicalResource::class.java) {
	val ID: ResourceLocation = "mekanism" % "chemical"

	override val id: ResourceLocation get() = ID

	override val genericPipeCarriage: PipeCarriage get() = PipeCarriage.PRIMARY

	override fun managerFor(level: ServerLevel): AbstractPipeNetworkManager<*> = ChemicalNetworkManager.get(level)

	override val router: PipeRouter<ChemicalResource> get() = ChemicalPipeRouter

	override val api: BlockLookup<CommonStorage<ChemicalResource>, Direction?> get() = ChemicalApi.BLOCK

	/** One bucket-equivalent per extraction. Mekanism counts chemicals in millibuckets directly, so unlike a fluid this needs no platform conversion. */
	override val extractionBatch: Long get() = 1_000L

	/** A stalled chemical has nowhere to go and no world form to drop, so it is voided - the same call the fluid network makes, and reported for the same reason. */
	override fun onJam(level: ServerLevel, pos: BlockPos, stack: SResourceStack<ChemicalResource>) {
		ResourceTrace.lost(
			pos, "pipe.jam", stack.resource, stack.amount, "voided, a chemical has no world form to drop",
		)
	}
}
