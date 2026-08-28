package net.kernelpanicsoft.boilerplate.pipe.hook

import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.ResourceStack
import net.kernelpanicsoft.archie.transfer.ArchieItemStorage
import net.kernelpanicsoft.archie.util.rem
import net.kernelpanicsoft.boilerplate.Boilerplate
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.gui.RequesterHookMenu
import net.kernelpanicsoft.boilerplate.pipe.network.RequestFulfillment
import net.kernelpanicsoft.boilerplate.pipe.network.SubnetBoundary
import net.kernelpanicsoft.boilerplate.registry.ItemRegistry
import net.kernelpanicsoft.boilerplate.registry.NetworkTypeRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.Item

/**
 * Periodically checks the attached (non-pipe) inventory against [RequesterHookState.request]'s
 * standing order and, if it's short, asks [RequestFulfillment] to top it back up - see
 * `docs/design/m3-warehouse-storage.md`.
 *
 * Facing an [InterfaceHookType] hook flips this hook's whole role, per
 * `docs/design/m2-sorting-routing.md`'s subnet boundary section: instead of requesting *for
 * itself* (topping up its own [RequesterHookState.request] order), it becomes an active supplier
 * for the interface's own [InterfaceHookState.stock] - "keeps the subnet in stock." See
 * [trySupplyInterface].
 */
object RequesterHookType : PipeHookType<RequesterHookState>() {
	val ID: ResourceLocation = Boilerplate.MOD % "requester"

	override val id: ResourceLocation get() = ID

	/** Attachable only on an item-pipe segment (see [net.kernelpanicsoft.boilerplate.pipe.attachment.PipeAttachmentType.compatibleNetworkTypes]). */
	override val compatibleNetworkTypes = setOf(NetworkTypeRegistry.Item)

	/** [PipeHookType.basePressureCost] - Its own periodic request is real per-tick work, but a single simple request - a middling draw. */
	override val basePressureCost: Long = 2L

	override fun createState(): RequesterHookState = RequesterHookState()

	override val hasMenu: Boolean = true

	override fun createMenu(
		id: Int,
		inventory: Inventory,
		tile: MultipartBlockEntity,
		direction: Direction
	): AbstractContainerMenu = RequesterHookMenu(id, inventory, tile, direction)

	override fun tick(level: ServerLevel, pos: BlockPos, direction: Direction, tile: MultipartBlockEntity, state: RequesterHookState) {
		state.ticksSinceRequest++
		if (state.ticksSinceRequest < REQUEST_INTERVAL_TICKS) return
		state.ticksSinceRequest = 0
		tryRequest(level, pos, direction, state)
	}

	private fun tryRequest(level: ServerLevel, pos: BlockPos, direction: Direction, state: RequesterHookState) {
		val neighborPos = pos.relative(direction)

		val interfaceState = SubnetBoundary.interfaceAt(level, neighborPos, direction.opposite)
		if (interfaceState != null) {
			trySupplyInterface(level, pos, neighborPos, direction.opposite, interfaceState.stock)
			return
		}

		val order = state.request[0]
		if (order.resource.isBlank) return

		val storage = ItemApi.BLOCK.find(level, neighborPos, direction.opposite) ?: return
		var current = 0L
		for (i in 0 until storage.size()) if (storage.getResource(i) == order.resource) current += storage.getAmount(i)

		val shortfall = order.amount - current
		if (shortfall <= 0) return
		RequestFulfillment.request(level, pos, ResourceStack(order.resource, shortfall), neighborPos, direction.opposite)
	}

	/**
	 * One [RequestFulfillment.request] call per non-blank, under-target slot in [stock] - see this
	 * type's own KDoc. [interfaceFace] is [direction]'s own opposite - the specific face of
	 * [interfacePos] this [stock] actually belongs to, disambiguating a block that carries more than
	 * one [InterfaceHookType] hook (see [net.kernelpanicsoft.boilerplate.pipe.entity.TravelingItem.targetFace]'s
	 * own KDoc).
	 */
	private fun trySupplyInterface(level: ServerLevel, pos: BlockPos, interfacePos: BlockPos, interfaceFace: Direction, stock: ArchieItemStorage) {
		for (i in 0 until stock.size()) {
			val slot = stock.get(i)
			val resource = slot.resource
			if (resource.isBlank) continue
			val shortfall = slot.getLimit(resource) - slot.amount
			if (shortfall <= 0) continue
			RequestFulfillment.request(level, pos, ResourceStack(resource, shortfall), interfacePos, interfaceFace)
		}
	}

	const val REQUEST_INTERVAL_TICKS = 40

	override fun asItem(): Item = ItemRegistry.RequesterHook
}
