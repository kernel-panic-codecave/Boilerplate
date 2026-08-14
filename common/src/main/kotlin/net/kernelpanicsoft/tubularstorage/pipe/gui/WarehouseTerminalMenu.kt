package net.kernelpanicsoft.tubularstorage.pipe.gui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import earth.terrarium.common_storage_lib.item.ItemApi
import earth.terrarium.common_storage_lib.resources.item.ItemResource
import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.archie.serialization.serializers.SItemStack
import net.kernelpanicsoft.tubularstorage.network.RequestWarehouseSearchResultsPacket
import net.kernelpanicsoft.tubularstorage.network.TubularStorageNetworkChannel
import net.kernelpanicsoft.tubularstorage.network.WarehouseSearchResultsPacket
import net.kernelpanicsoft.tubularstorage.network.WithdrawFromWarehousePacket
import net.kernelpanicsoft.tubularstorage.pipe.entity.HookBlockEntity
import net.kernelpanicsoft.tubularstorage.pipe.network.RequestFulfillment
import net.kernelpanicsoft.tubularstorage.registry.GuiRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory

/**
 * Menu for the warehouse terminal hook attached to [tile]: search/withdraw across *every* source
 * reachable from [tile]'s own pipe position, not just bound warehouses - a
 * [net.kernelpanicsoft.tubularstorage.pipe.hook.ProviderHookType]-tagged inventory is just as
 * searchable, the same two sources [RequestFulfillment.request] already draws a standing order
 * from (and, once M4 exists, on-demand crafts would be a third) - see
 * `docs/design/m3-warehouse-storage.md`. No block-owned slots of its own; the result list is a
 * virtual, non-slot-backed view (see `WarehouseTerminalScreen`), not real vanilla
 * [net.minecraft.world.inventory.Slot]s, since warehouse contents can vastly exceed the usual
 * ~45-slot menu ceiling.
 *
 * A withdrawal always has one well-defined destination: whatever inventory is directly connected
 * to [tile]'s own other faces (see [adjacentInventory]) - the same targeted routing
 * ([RequestFulfillment.request]) a [net.kernelpanicsoft.tubularstorage.pipe.hook.RequesterHookType]
 * standing order uses, not a dumb network push that could land anywhere, including straight back
 * into the warehouse it came from. If nothing's plugged into this terminal's own pipe, there's
 * nowhere for a withdrawal to go, so it's a no-op.
 */
class WarehouseTerminalMenu(id: Int, inventory: Inventory, tile: HookBlockEntity) :
	ComposeBlockContainerMenu<HookBlockEntity, WarehouseTerminalMenu>(GuiRegistry.WarehouseTerminal, id, inventory, tile) {

	/** The most recently received search results - Compose state, so [WarehouseTerminalScreen] recomposes whenever [updateResults] applies a fresh [WarehouseSearchResultsPacket]. */
	var results: List<SItemStack> by mutableStateOf(emptyList())
		private set

	override fun registerSlotHandlers() {}

	/**
	 * Requests fresh results from the client side, rather than the server eagerly pushing them the
	 * moment its own menu instance is constructed - the server's own construction (and so this
	 * `onMenuOpened` firing there) happens *before* the client has necessarily finished opening the
	 * screen and become the active `containerMenu`, so an eager server push routinely lost the race
	 * and got silently dropped by [WarehouseSearchResultsPacket.handleOnClient]'s own `containerMenu`
	 * cast, requiring a manual refresh click to ever populate anything. The client's own
	 * `onMenuOpened` only fires once its menu construction is what NeoForge/Fabric already resolved
	 * as the active menu, so a request sent from there can't lose that race.
	 */
	override fun onMenuOpened() {
		super.onMenuOpened()
		if (level.isClientSide) TubularStorageNetworkChannel.toServer(RequestWarehouseSearchResultsPacket)
	}

	/** Client-side: applies a freshly received [WarehouseSearchResultsPacket]. */
	fun updateResults(results: List<SItemStack>) {
		this.results = results
	}

	/** Recomputes the aggregated contents of every source reachable from [tile]'s own position and sends it to this menu's own player. */
	fun sendSearchResults() {
		val level = level as? ServerLevel ?: return
		val totals = LinkedHashMap<ItemResource, Long>()
		fun add(resource: ItemResource, amount: Long) {
			if (resource.isBlank || amount <= 0) return
			totals[resource] = (totals[resource] ?: 0L) + amount
		}

		for (source in RequestFulfillment.reachableProviders(level, tile.blockPos)) {
			val storage = source.storage(level) ?: continue
			for (i in 0 until storage.size()) add(storage.getResource(i), storage.getAmount(i))
		}
		for (warehouse in RequestFulfillment.reachableWarehouses(level, tile.blockPos)) {
			for ((resource, entries) in warehouse.index.locations) add(resource, entries.sumOf { it.amount })
		}

		val stacks = totals.map { (resource, amount) -> resource.toStack(amount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
		TubularStorageNetworkChannel.toPlayer(player as ServerPlayer, WarehouseSearchResultsPacket(stacks))
	}

	/**
	 * Requests up to [amount] of [resource] be delivered to [adjacentInventory] - a reachable
	 * provider or warehouse, whichever [RequestFulfillment.request] finds first, exactly as a
	 * [net.kernelpanicsoft.tubularstorage.pipe.hook.RequesterHookType] standing order would. Re-sends
	 * fresh results either way, reflecting whatever the withdrawal actually took - immediately
	 * accurate for a provider (an ordinary synchronous CSL extract), but only once the gantry
	 * physically finishes for a warehouse-sourced one, which is what [requestWithdraw]'s own
	 * client-side optimistic update is for.
	 */
	fun withdraw(resource: ItemResource, amount: Long) {
		val level = level as? ServerLevel ?: return
		val destination = adjacentInventory(level) ?: return
		RequestFulfillment.request(level, tile.blockPos, resource, amount, destination)
		sendSearchResults()
	}

	/**
	 * Client-side: sends a withdrawal request for [amount] of [resource] and immediately reflects it
	 * in [results] itself, rather than waiting on a round trip back from the server - which, for a
	 * warehouse-sourced withdrawal, only arrives once the gantry physically finishes the retrieval
	 * (see [withdraw]'s KDoc), not the instant the request is made. Whatever the server's own next
	 * [WarehouseSearchResultsPacket] says (that eventual completion, a manual refresh, or simply the
	 * post-[withdraw] resync) still overwrites this guess with the authoritative total.
	 */
	fun requestWithdraw(resource: ItemResource, amount: Long) {
		results = results.mapNotNull { stack ->
			if (ItemResource.of(stack) != resource) return@mapNotNull stack
			val remaining = stack.count - amount
			if (remaining <= 0) null else stack.copyWithCount(remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
		}
		TubularStorageNetworkChannel.toServer(WithdrawFromWarehousePacket(resource.toStack(amount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())))
	}

	/** The position of the first inventory directly attached to one of [tile]'s own six faces, or `null` if nothing's plugged in - the well-defined destination every [withdraw] delivers to. */
	private fun adjacentInventory(level: ServerLevel): BlockPos? {
		for (direction in Direction.entries) {
			val neighborPos = tile.blockPos.relative(direction)
			if (ItemApi.BLOCK.find(level, neighborPos, direction.opposite) != null) return neighborPos
		}
		return null
	}
}
