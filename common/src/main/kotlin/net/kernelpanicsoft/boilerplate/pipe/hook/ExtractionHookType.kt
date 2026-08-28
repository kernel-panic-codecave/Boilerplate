package net.kernelpanicsoft.boilerplate.pipe.hook

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.ResourceStack
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.block.PipeBlock
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem
import net.kernelpanicsoft.boilerplate.pipe.network.PipeRouter
import net.kernelpanicsoft.boilerplate.pipe.network.SubnetBoundary
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.registry.NetworkTypeRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.Item

/**
 * Periodically pulls a resource from the (non-pipe) inventory on the attached face and, if the
 * network can route it somewhere that will accept it, spawns it as a [TravelingItem]. The only
 * self-initiating hook - a [FilterHookType] hook never pulls on its own.
 *
 * Facing an [InterfaceHookType] hook directly is the one case where the neighbor genuinely *is*
 * "a pipe" (another [MultipartBlockEntity]) and this hook still pulls from it anyway - the subnet
 * boundary's own active-extract role (`docs/design/m2-sorting-routing.md`), reaching across into
 * whatever that interface's own [InterfaceHookState.stock] currently holds.
 */
object ExtractionHookType : PipeHookType<ExtractionHookState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "extraction"

	override val id: ResourceLocation get() = ID

	/** Attachable only on an item-pipe segment (see [net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]). */
	override val compatibleNetworkTypes = setOf(NetworkTypeRegistry.Item)

	/** [PipeHookType.basePressureCost] - Its own periodic pull is real per-tick work, but a single simple extraction - a middling draw. */
	override val basePressureCost: Long = 2L

	override fun createState(): ExtractionHookState = ExtractionHookState()

	/**
	 * Gated entirely on [basePressureCost] now - see [MultipartBlockEntity.tick]'s own draw/gate,
	 * which already skips this call altogether without it - so this just ticks at the flat
	 * [EXTRACTION_INTERVAL_TICKS], no separate speed-bonus draw of its own on top.
	 */
	override fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: ExtractionHookState) {
		state.ticksSinceExtraction++
		if (state.ticksSinceExtraction < EXTRACTION_INTERVAL_TICKS) return
		state.ticksSinceExtraction = 0
		tryExtract(level, pos, direction, tile, state)
	}

	private fun tryExtract(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: ExtractionHookState) {
		// Not a filter on what's pulled (see docs/design/m2-sorting-routing.md) - just the color
		// tag this hook stamps on whatever it sends out.
		val color = state.color

		val neighborPos = pos.relative(direction)
		// A hook-carrying neighbor is normally still "a pipe" for this guard's purposes (nothing
		// to extract from) - except an InterfaceHookType hook facing this one, a subnet boundary
		// this hook is deliberately allowed to reach across (see `docs/design/m2-sorting-routing.md`).
		if (level.getBlockState(neighborPos).block is PipeBlock && !SubnetBoundary.isBoundaryEdge(level, pos, direction)) return
		val storage = ItemApi.BLOCK.find(level, neighborPos, direction.opposite) ?: return

		for (slotIndex in 0 until storage.size()) {
			val resource = storage.get(slotIndex).resource
			if (resource.isBlank) continue

			val available = storage.extract(resource, EXTRACTION_AMOUNT, true)
			if (available <= 0) continue

			val route = PipeRouter.findRoute(level, pos, resource, color, exclude = neighborPos) ?: continue

			val extracted = storage.extract(resource, available, false)
			if (extracted <= 0) continue

			tile.travelingItems += TravelingItem(ResourceStack(resource, extracted), direction, 0f, route, color)
			return
		}
	}

	const val EXTRACTION_INTERVAL_TICKS = 10

	const val EXTRACTION_AMOUNT = 64L

	override fun asItem(): Item = ItemRegistry.ExtractionHook
}
