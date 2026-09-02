package net.kernelpanicsoft.boilerplate.pipe.gui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.kernelpanicsoft.archie.gui.ComposeBlockContainerMenu
import net.kernelpanicsoft.boilerplate.network.BoilerplateNetworkChannel
import net.kernelpanicsoft.boilerplate.network.RequestRequesterStatusPacket
import net.kernelpanicsoft.boilerplate.network.RequesterStatusPacket
import net.kernelpanicsoft.boilerplate.pipe.entity.MultipartBlockEntity
import net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookState
import net.kernelpanicsoft.boilerplate.pipe.hook.StockingRow
import net.kernelpanicsoft.boilerplate.pipe.hook.RequesterHookType
import net.kernelpanicsoft.boilerplate.pipe.hook.requesterStatus
import net.kernelpanicsoft.boilerplate.registry.GuiRegistry
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Inventory

/**
 * Menu for the requester hook attached to [tile]'s [direction] face - its
 * [net.kernelpanicsoft.boilerplate.pipe.hook.StockingRow] of standing orders (what to keep supplied
 * on the far side, and how much of each), plus the live [status] readout its screen shows.
 */
class RequesterHookMenu(id: Int, inventory: Inventory, tile: MultipartBlockEntity, val direction: Direction) :
	ComposeBlockContainerMenu<MultipartBlockEntity, RequesterHookMenu>(GuiRegistry.RequesterHook, id, inventory, tile), StockingRowMenu
{
	override val row: StockingRow? get() = tile.hooks[direction.name] as? RequesterHookState

	// No real slots: the whole configuration is the ghost row, edited through StockingRowMenu.
	override fun registerSlotHandlers() = Unit

	/** What this hook is currently doing, as last reported by the server - see [RequesterStatusPacket]. `null` until the first reply arrives. */
	var status: RequesterStatusPacket? by mutableStateOf(null)
		private set

	/** Client-side: asks the server for [status]'s current value. */
	fun requestStatus() {
		BoilerplateNetworkChannel.toServer(RequestRequesterStatusPacket)
	}

	/** Client-side: applies [requestStatus]'s reply. */
	fun applyStatus(packet: RequesterStatusPacket) {
		status = packet
	}

	/**
	 * Server-side: computes and replies with what this hook is actually doing.
	 *
	 * The computation itself lives beside [RequesterHookType] as [requesterStatus], so it walks the
	 * same decision the hook walks each cycle rather than a parallel copy free to drift from it.
	 */
	fun sendStatus() {
		val level = level as? ServerLevel ?: return
		val player = player as? ServerPlayer ?: return
		val state = tile.hooks[direction.name] as? RequesterHookState ?: return
		BoilerplateNetworkChannel.toPlayer(player, requesterStatus(level, tile.blockPos, direction, state))
	}
}
